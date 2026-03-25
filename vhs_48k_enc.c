#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <math.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/time.h>
#include <linux/fb.h>
#include <alsa/asoundlib.h>
#include <pthread.h>

#define WIDTH 720
#define HEIGHT 480
#define FB_W 1024
#define FB_DEV "/dev/fb0"
#define C_WHITE 0xFFFFFFFF
#define C_BLACK 0xFF000000

#define BITS_PER_LINE 152
#define BIT_WIDTH 4
#define PIXEL_OFFSET ((WIDTH - (BITS_PER_LINE * BIT_WIDTH)) / 2) + 1

#define VBLANK_LINES 40
#define AUDIO_LINES 400
#define SAMPLES_PER_LINE 4

#define PCM_DEVICE "hw:0,0"
#define ALSA_PERIOD 1024
#define VIDEO_CHUNK 1600
#define RING_SIZE (ALSA_PERIOD * 32)
#define AMPLITUDE 16000.0

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

snd_pcm_t *pcm_handle, *pcm_out_handle;
int16_t ring_buffer[RING_SIZE * 2];
volatile int head = 0, tail = 0, encode_tail = 0, audio_ready = 0;
int use_alsa = 1, monitor_audio = 0;
double current_freq = 400.0;
int sweep_interval_seconds = 5;
double sweep_increment_hz = 200.0;

uint16_t crc16_ccitt(uint8_t *bits, int offset, int len) {
  uint16_t crc = 0x0000;
  for (int i = 0; i < len; i++) {
    uint8_t crc_bit = (crc & 0x8000) ? 1 : 0;
    crc <<= 1;
    if (bits[offset + i] ^ crc_bit) crc ^= 0x1021;
  }
  return crc;
}

void generate_sine_sweep(int16_t *samples, int total_samples, double sample_rate) {
  static double phase = 0.0;
  static int sweep_sample_count = 0;
  int noise_level = (int)(AMPLITUDE * 0.10);
  for(int i = 0; i < total_samples; i += 2) {
    int16_t base_val = (int16_t)(sin(phase) * AMPLITUDE);
    int16_t noise_l = (rand() % (noise_level * 2)) - noise_level;
    int16_t noise_r = (rand() % (noise_level * 2)) - noise_level;
    samples[i] = base_val + noise_l;
    samples[i+1] = base_val + noise_r;
    phase += 2.0 * M_PI * current_freq / sample_rate;
    if (phase >= 2.0 * M_PI) phase -= 2.0 * M_PI;
    
    sweep_sample_count++;
    if (sweep_sample_count >= (int)(sample_rate * sweep_interval_seconds)) {
      current_freq += sweep_increment_hz;
      if (current_freq > 16000.0) current_freq = 400.0;
      sweep_sample_count = 0;
    }
  }
}

int init_alsa_capture() {
  snd_pcm_hw_params_t *params;
  unsigned int rate = 48000;
  if (snd_pcm_open(&pcm_handle, PCM_DEVICE, SND_PCM_STREAM_CAPTURE, 0) < 0) return -1;
  snd_pcm_hw_params_alloca(&params);
  snd_pcm_hw_params_any(pcm_handle, params);
  snd_pcm_hw_params_set_access(pcm_handle, params, SND_PCM_ACCESS_RW_INTERLEAVED);
  snd_pcm_hw_params_set_format(pcm_handle, params, SND_PCM_FORMAT_S16_LE);
  snd_pcm_hw_params_set_channels(pcm_handle, params, 2);
  snd_pcm_hw_params_set_rate_near(pcm_handle, params, &rate, 0);
  snd_pcm_hw_params_set_period_size(pcm_handle, params, ALSA_PERIOD, 0);
  snd_pcm_hw_params_set_buffer_size(pcm_handle, params, ALSA_PERIOD * 8);
  if (snd_pcm_hw_params(pcm_handle, params) < 0) return -1;
  snd_pcm_prepare(pcm_handle);
  return 0;
}

int init_alsa_playback() {
  snd_pcm_hw_params_t *params;
  unsigned int rate = 48000;
  if (snd_pcm_open(&pcm_out_handle, PCM_DEVICE, SND_PCM_STREAM_PLAYBACK, 0) < 0) return -1;
  snd_pcm_hw_params_alloca(&params);
  snd_pcm_hw_params_any(pcm_out_handle, params);
  snd_pcm_hw_params_set_access(pcm_out_handle, params, SND_PCM_ACCESS_RW_INTERLEAVED);
  snd_pcm_hw_params_set_format(pcm_out_handle, params, SND_PCM_FORMAT_S16_LE);
  snd_pcm_hw_params_set_channels(pcm_out_handle, params, 2);
  snd_pcm_hw_params_set_rate_near(pcm_out_handle, params, &rate, 0);
  snd_pcm_hw_params_set_period_size(pcm_out_handle, params, ALSA_PERIOD, 0);
  snd_pcm_hw_params_set_buffer_size(pcm_out_handle, params, ALSA_PERIOD * 8);
  if (snd_pcm_hw_params(pcm_out_handle, params) < 0) return -1;
  snd_pcm_prepare(pcm_out_handle);
  return 0;
}

void *capture_thread(void *arg) {
  int err;
  struct sched_param param = { .sched_priority = 99 };
  pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);
  int16_t tmp[ALSA_PERIOD * 2];
  while (1) {
    if (use_alsa) {
      if ((err = snd_pcm_readi(pcm_handle, tmp, ALSA_PERIOD)) != ALSA_PERIOD) {
        snd_pcm_prepare(pcm_handle);
        continue;
      }
      for (int i = 0; i < ALSA_PERIOD * 2; i++) {
        ring_buffer[head] = tmp[i]; head = (head + 1) % (RING_SIZE * 2);
      }
    } else {
      usleep(100000); // Just sleep, main thread handles sweep natively
    }
    if (!audio_ready && ((head - tail + (RING_SIZE * 2)) % (RING_SIZE * 2)) >= (ALSA_PERIOD * 16)) audio_ready = 1;
  }
  return NULL;
}

void *playback_thread(void *arg) {
  struct sched_param param = { .sched_priority = 95 };
  pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);
  int16_t out_tmp[ALSA_PERIOD * 2];
  while (1) {
    if (!audio_ready) { usleep(10000); continue; }
    int avail = (head - tail + (RING_SIZE * 2)) % (RING_SIZE * 2);
    if (avail < ALSA_PERIOD * 2) { usleep(1000); continue; }
    for (int i = 0; i < ALSA_PERIOD * 2; i++) {
      out_tmp[i] = ring_buffer[tail]; tail = (tail + 1) % (RING_SIZE * 2);
    }
    int err = snd_pcm_writei(pcm_out_handle, out_tmp, ALSA_PERIOD);
    if (err < 0) {
      snd_pcm_prepare(pcm_out_handle);
      audio_ready = 0;
      tail = (tail - (ALSA_PERIOD * 2) + (RING_SIZE * 2)) % (RING_SIZE * 2);
    }
  }
  return NULL;
}

int main(int argc, char *argv[]) {
  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "-s") == 0) use_alsa = 0;
    else if (strcmp(argv[i], "-a") == 0) monitor_audio = 1;
  }
  
  if (use_alsa) {
    printf("Initializing Pure 48K VHS ALSA Capture...\n");
    if (init_alsa_capture() < 0) { printf("Failed ALSA Capture\n"); return -1; }
  } else {
    printf("Initializing Pure 48K VHS Tone Sweep Generator...\n");
  }

  if (monitor_audio) {
    printf("Initializing Hardware for ALSA Playback Monitor...\n");
    if (init_alsa_playback() < 0) { printf("Failed ALSA Playback\n"); return -1; }
    pthread_t pb_tid;
    pthread_create(&pb_tid, NULL, playback_thread, NULL);
  }

  pthread_t cap_tid;
  if (pthread_create(&cap_tid, NULL, capture_thread, NULL) != 0) return -1;

  int fb_fd = open(FB_DEV, O_RDWR);
  if (fb_fd < 0) { printf("Failed FB\n"); return -1; }
  uint32_t *fb = mmap(NULL, FB_W * 600 * 4, PROT_READ|PROT_WRITE, MAP_SHARED, fb_fd, 0);

  int16_t curr_hw_audio[VIDEO_CHUNK * 2];
  int video_frames = 0;
  struct timeval t1, t2, target_time;
  gettimeofday(&target_time, NULL);
  printf("Ready. Encoding Video...\n");

  long last_us = 0;

  while (1) {
    if (use_alsa) {
      int avail = (head - encode_tail + (RING_SIZE * 2)) % (RING_SIZE * 2);
      if (avail < VIDEO_CHUNK * 2) { usleep(1000); continue; }
      for (int i = 0; i < VIDEO_CHUNK * 2; i++) {
        curr_hw_audio[i] = ring_buffer[encode_tail];
        encode_tail = (encode_tail + 1) % (RING_SIZE * 2);
      }
    } else {
      generate_sine_sweep((int16_t *)curr_hw_audio, VIDEO_CHUNK * 2, 48000.0);
      if (monitor_audio) {
        for (int i = 0; i < VIDEO_CHUNK * 2; i++) {
          ring_buffer[head] = curr_hw_audio[i];
          head = (head + 1) % (RING_SIZE * 2);
        }
        if (!audio_ready && ((head - tail + (RING_SIZE * 2)) % (RING_SIZE * 2)) >= (ALSA_PERIOD * 16)) audio_ready = 1;
      }
    }

    gettimeofday(&t1, NULL);

    int dummy = 0;
    int vsync_supported = (ioctl(fb_fd, FBIO_WAITFORVSYNC, &dummy) == 0);

    int peak_l = 0, peak_r = 0;
    for (int i=0; i<VIDEO_CHUNK; i++) {
        int l = abs((int)curr_hw_audio[i*2]);
        int r = abs((int)curr_hw_audio[i*2+1]);
        if (l > peak_l) peak_l = l;
        if (r > peak_r) peak_r = r;
    }

    char dynamic_metadata[17];
    snprintf(dynamic_metadata, sizeof(dynamic_metadata), "V1.0:%6ld us  ", last_us);
    for (int i = strlen(dynamic_metadata); i < 16; i++) dynamic_metadata[i] = ' ';
    dynamic_metadata[16] = '\0';

    int audio_idx = 0;
    uint8_t bits[BITS_PER_LINE];
    for (int y = 0; y < HEIGHT; y++) {
      uint32_t *dest_row = fb + (y * FB_W);
      memset(dest_row, 0, WIDTH * 4); // Clear analog garbage on horizontal edges

      if (y >= VBLANK_LINES + AUDIO_LINES) {
        if (y >= 450 && y <= 455) {
          int bar_width = (peak_l * (BITS_PER_LINE * BIT_WIDTH)) / 32768;
          for (int x = 0; x < bar_width; x++) dest_row[PIXEL_OFFSET + x] = C_WHITE;
        } else if (y >= 460 && y <= 465) {
          int bar_width = (peak_r * (BITS_PER_LINE * BIT_WIDTH)) / 32768;
          for (int x = 0; x < bar_width; x++) dest_row[PIXEL_OFFSET + x] = C_WHITE;
        }
        continue;
      }

      if (y < VBLANK_LINES) {
        if (y >= 20 && y <= 29) {
          memset(bits, 0, sizeof(bits));
          bits[0]=1; bits[1]=0; bits[2]=1; bits[3]=0;
          for(int b=0; b<4; b++) bits[4+b] = 0;
          for (int c=0; c<16; c++) {
            char ch = dynamic_metadata[c];
            for (int b=0; b<8; b++) bits[8+(c*8)+b] = ((ch >> (7-b)) & 1);
          }
          uint16_t crc = crc16_ccitt(bits, 4, 132);
          for (int b=0; b<16; b++) bits[136+b] = ((crc>>(15-b))&1);
          for (int b=0; b<BITS_PER_LINE; b++) {
            uint32_t color = bits[b] ? C_WHITE : C_BLACK;
            for (int px=0; px<BIT_WIDTH; px++) dest_row[PIXEL_OFFSET+(b*BIT_WIDTH)+px] = color;
          }
        }
        continue;
      }

      memset(bits, 0, sizeof(bits));
      bits[0]=1; bits[1]=0; bits[2]=1; bits[3]=0;
      int frame_id = video_frames % 16;
      bits[4] = (frame_id >> 3) & 1;
      bits[5] = (frame_id >> 2) & 1;
      bits[6] = (frame_id >> 1) & 1;
      bits[7] = frame_id & 1;
      
      uint16_t words_to_pack[8];
      for(int i=0; i<8; i++) words_to_pack[i] = curr_hw_audio[audio_idx++];

      for (int w_idx=0; w_idx<8; w_idx++) {
        for(int b=0; b<16; b++) {
          bits[8+(w_idx*16)+b] = ((words_to_pack[w_idx]>>(15-b))&1);
        }
      }

      uint16_t crc = crc16_ccitt(bits, 4, 132);
      for (int b=0; b<16; b++) bits[136+b] = ((crc>>(15-b))&1);

      for (int b=0; b<BITS_PER_LINE; b++) {
        uint32_t color = bits[b] ? C_WHITE : C_BLACK;
        for (int px=0; px<BIT_WIDTH; px++) dest_row[PIXEL_OFFSET+(b*BIT_WIDTH)+px] = color;
      }
    }

    gettimeofday(&t2, NULL);
    long us = (t2.tv_sec - t1.tv_sec) * 1000000 + (t2.tv_usec - t1.tv_usec);
    last_us = us;

    if (++video_frames % 30 == 0) {
      if (use_alsa) printf("\rRender: %ld us | FPS: 30 | VSYNC: %s | ALSA In      ", us, vsync_supported ? "ON" : "OFF");
      else printf("\rRender: %ld us | FPS: 30 | VSYNC: %s | %5.0f Hz    ", us, vsync_supported ? "ON" : "OFF", current_freq);
      fflush(stdout);
    }

    if (!use_alsa) {
      target_time.tv_usec += 33333; 
      if (target_time.tv_usec >= 1000000) { target_time.tv_sec += 1; target_time.tv_usec -= 1000000; }
      struct timeval current_time;
      gettimeofday(&current_time, NULL);
      long delay_us = (target_time.tv_sec - current_time.tv_sec) * 1000000 + (target_time.tv_usec - current_time.tv_usec);
      if (delay_us > 0) usleep(delay_us);
    }
  }
}
