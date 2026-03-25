#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <string.h>
#include <time.h>
#include <sys/time.h>
#include <math.h>
#include <alsa/asoundlib.h>
#include <pthread.h>
#include <sched.h>
#include <linux/fb.h>
#include <sys/ioctl.h>

#define WIDTH 720
#define HEIGHT 480
#define FB_W 1024
#define FB_DEV "/dev/fb0"
#define C_WHITE 0xFFFFFFFF
#define C_BLACK 0xFF000000

#define BITS_PER_LINE 137
#define BIT_WIDTH 5
#define PIXEL_OFFSET ((WIDTH - (BITS_PER_LINE * BIT_WIDTH)) / 2) + 1
#define VBLANK_LINES 64
#define AUDIO_LINES 352
#define SAMPLES_PER_LINE 3

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

/* --- Interleave Delay Matrix State --- */
static uint16_t enc_hist_w[128][6] = {0};
static uint16_t enc_hist_p[128] = {0};
static uint16_t enc_hist_q[128] = {0};
static long global_enc_line = 0;

/* --- Math --- */
uint16_t crc16_ccitt(uint8_t *bits, int offset, int len) {
  uint16_t crc = 0x0000;
  for (int i = 0; i < len; i++) {
    uint8_t crc_bit = (crc & 0x8000) ? 1 : 0;
    crc <<= 1;
    if (bits[offset + i] ^ crc_bit) crc ^= 0x1021;
  }
  return crc;
}

int16_t cubic_interp(int16_t p0, int16_t p1, int16_t p2, int16_t p3, double x) {
    double a = -0.5*p0 + 1.5*p1 - 1.5*p2 + 0.5*p3;
    double b = p0 - 2.5*p1 + 2.0*p2 - 0.5*p3;
    double c = -0.5*p0 + 0.5*p2;
    double d = p1;
    double val = a*x*x*x + b*x*x + c*x + d;
    if (val > 32767.0) return 32767;
    if (val < -32768.0) return -32768;
    return (int16_t)val;
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

/* --- ALSA Hardware Init --- */
int setup_pcm(snd_pcm_t *handle, unsigned int rate) {
  snd_pcm_hw_params_t *params;
  snd_pcm_hw_params_alloca(&params);
  snd_pcm_hw_params_any(handle, params);
  snd_pcm_hw_params_set_access(handle, params, SND_PCM_ACCESS_RW_INTERLEAVED);
  snd_pcm_hw_params_set_format(handle, params, SND_PCM_FORMAT_S16_LE);
  snd_pcm_hw_params_set_channels(handle, params, 2);
  snd_pcm_hw_params_set_rate_near(handle, params, &rate, 0);
  snd_pcm_hw_params_set_period_size(handle, params, ALSA_PERIOD, 0);
  snd_pcm_hw_params_set_buffer_size(handle, params, ALSA_PERIOD * 8);
  return snd_pcm_hw_params(handle, params);
}

/* --- Threads --- */
void *capture_thread(void *arg) {
  struct sched_param param = { .sched_priority = 95 };
  pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);
  int16_t tmp[ALSA_PERIOD * 2];

  while (1) {
    if (use_alsa) {
      int err = snd_pcm_readi(pcm_handle, tmp, ALSA_PERIOD);
      if (err == -EPIPE) { snd_pcm_prepare(pcm_handle); continue; }
      else if (err < 0) continue;
      for (int i = 0; i < err * 2; i++) {
	ring_buffer[head] = tmp[i]; head = (head + 1) % (RING_SIZE * 2);
      }
    } else {
      int used = (head - encode_tail + (RING_SIZE * 2)) % (RING_SIZE * 2);
      if (used > (RING_SIZE * 2) - (ALSA_PERIOD * 4)) {
	usleep(2000);
	continue;
      }

      generate_sine_sweep(tmp, ALSA_PERIOD * 2, 48000.0);
      for (int i = 0; i < ALSA_PERIOD * 2; i++) {
	ring_buffer[head] = tmp[i]; head = (head + 1) % (RING_SIZE * 2);
      }
    }
    if (!audio_ready && head >= (ALSA_PERIOD * 8)) audio_ready = 1;
  }
}

void *playback_thread(void *arg) {
  struct sched_param param = { .sched_priority = 90 };
  pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);
  int16_t out_tmp[ALSA_PERIOD * 2];

  while (1) {
    if (!audio_ready) { usleep(10000); continue; }
    int avail = (head - tail + (RING_SIZE * 2)) % (RING_SIZE * 2);
    if (avail < ALSA_PERIOD * 2) { usleep(1000); continue; }

    for (int i = 0; i < ALSA_PERIOD * 2; i++) {
      out_tmp[i] = ring_buffer[tail]; tail = (tail + 1) % (RING_SIZE * 2);
    }
    if (snd_pcm_writei(pcm_out_handle, out_tmp, ALSA_PERIOD) < 0) snd_pcm_prepare(pcm_out_handle);
  }
}

int main(int argc, char *argv[]) {
  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "-s") == 0) use_alsa = 0;
    else if (strcmp(argv[i], "-a") == 0) monitor_audio = 1;
  }

  srand(time(NULL));

  if (use_alsa) {
    if (snd_pcm_open(&pcm_handle, PCM_DEVICE, SND_PCM_STREAM_CAPTURE, 0) < 0 || setup_pcm(pcm_handle, 48000) < 0) {
      fprintf(stderr, "Failed to initialize ALSA Capture.\n"); return 1;
    }
    snd_pcm_prepare(pcm_handle);
  }

  if (monitor_audio) {
    if (snd_pcm_open(&pcm_out_handle, PCM_DEVICE, SND_PCM_STREAM_PLAYBACK, 0) < 0 || setup_pcm(pcm_out_handle, 48000) < 0) {
      fprintf(stderr, "Failed to initialize ALSA Playback.\n"); return 1;
    }
    snd_pcm_prepare(pcm_out_handle);
  }

  int fb_fd = open(FB_DEV, O_RDWR);
  if (fb_fd < 0) return 1;
  uint32_t *fb = mmap(NULL, FB_W * 600 * 4, PROT_READ|PROT_WRITE, MAP_SHARED, fb_fd, 0);

  printf("Mode: %s | Monitor: %s\n", use_alsa ? "ALSA Line-In" : "Internal Sweep", monitor_audio ? "ON" : "OFF");
  printf("Pulse-Free Encoder Running. Press Ctrl+C to stop.\n");

  pthread_t tid1, tid2;
  pthread_create(&tid1, NULL, capture_thread, NULL);
  if (monitor_audio) pthread_create(&tid2, NULL, playback_thread, NULL);

  int alsa_frames_needed = AUDIO_LINES * SAMPLES_PER_LINE;
  int16_t frame_audio[alsa_frames_needed * 2];
  int16_t curr_hw_audio[VIDEO_CHUNK * 2], prev_hw_audio[VIDEO_CHUNK * 2];
  int16_t hw_tail[2][2] = {{0}};
  int first_frame = 1, video_frames = 0;
  struct timeval t1, t2, target_time;
  gettimeofday(&target_time, NULL);

  while (1) {
    if (!audio_ready) { usleep(10000); gettimeofday(&target_time, NULL); continue; }

    int avail = (head - encode_tail + (RING_SIZE * 2)) % (RING_SIZE * 2);
    if (avail < VIDEO_CHUNK * 2) { usleep(1000); continue; }

    for (int i = 0; i < VIDEO_CHUNK * 2; i++) {
      curr_hw_audio[i] = ring_buffer[encode_tail];
      encode_tail = (encode_tail + 1) % (RING_SIZE * 2);
    }

    if (first_frame) { 
      memcpy(prev_hw_audio, curr_hw_audio, sizeof(curr_hw_audio)); 
      hw_tail[0][0] = prev_hw_audio[(VIDEO_CHUNK - 2) * 2 + 0];
      hw_tail[0][1] = prev_hw_audio[(VIDEO_CHUNK - 2) * 2 + 1];
      hw_tail[1][0] = prev_hw_audio[(VIDEO_CHUNK - 1) * 2 + 0];
      hw_tail[1][1] = prev_hw_audio[(VIDEO_CHUNK - 1) * 2 + 1];
      first_frame = 0; 
      continue; 
    }

    gettimeofday(&t1, NULL);

    double ratio = (double)VIDEO_CHUNK / alsa_frames_needed;
    for (int i = 0; i < alsa_frames_needed; i++) {
      double exact_idx = i * ratio; 
      int idx1 = (int)floor(exact_idx); 
      double frac = exact_idx - idx1;
      if (idx1 < -1) { idx1 = -1; frac = 0.0; }

#define GET_SAMP(idx, ch) (((idx) < 0 ? hw_tail[2 + (idx)][(ch)] : \
			    ((idx) < VIDEO_CHUNK ? prev_hw_audio[(idx) * 2 + (ch)] : \
			    curr_hw_audio[((idx) - VIDEO_CHUNK) * 2 + (ch)])))

      frame_audio[i * 2] = cubic_interp(GET_SAMP(idx1-1,0), GET_SAMP(idx1,0), GET_SAMP(idx1+1,0), GET_SAMP(idx1+2,0), frac);
      frame_audio[i * 2 + 1] = cubic_interp(GET_SAMP(idx1-1,1), GET_SAMP(idx1,1), GET_SAMP(idx1+1,1), GET_SAMP(idx1+2,1), frac);
    }
    hw_tail[0][0] = prev_hw_audio[(VIDEO_CHUNK - 2) * 2 + 0];
    hw_tail[0][1] = prev_hw_audio[(VIDEO_CHUNK - 2) * 2 + 1];
    hw_tail[1][0] = prev_hw_audio[(VIDEO_CHUNK - 1) * 2 + 0];
    hw_tail[1][1] = prev_hw_audio[(VIDEO_CHUNK - 1) * 2 + 1];
    memcpy(prev_hw_audio, curr_hw_audio, sizeof(curr_hw_audio));

    int audio_idx = 0;
    uint8_t bits[BITS_PER_LINE];
    for (int y = 0; y < HEIGHT; y++) {
      uint32_t *dest_row = fb + (y * FB_W);
      if (y < VBLANK_LINES || y > VBLANK_LINES + AUDIO_LINES) { memset(dest_row, 0, WIDTH * 4); continue; }

      memset(bits, 0, sizeof(bits));
      bits[0]=0; bits[1]=1; bits[2]=0; bits[3]=1; bits[132]=1; bits[133]=1; bits[134]=1; bits[135]=1; bits[136]=0;

      if (y > VBLANK_LINES) {
	uint16_t w[6];
	for(int i = 0; i < 6; i++) w[i] = (frame_audio[audio_idx++] >> 2) & 0x3FFF;
	uint16_t p = w[0]^w[1]^w[2]^w[3]^w[4]^w[5];
	uint16_t q = ((frame_audio[audio_idx-6]&3)<<12)|((frame_audio[audio_idx-5]&3)<<10)|
	  ((frame_audio[audio_idx-4]&3)<<8) |((frame_audio[audio_idx-3]&3)<<6)|
	  ((frame_audio[audio_idx-2]&3)<<4) |((frame_audio[audio_idx-1]&3)<<2);

	int hist_idx = global_enc_line % 128;
	for(int i=0; i<6; i++) enc_hist_w[hist_idx][i] = w[i];
	enc_hist_p[hist_idx] = p;
	enc_hist_q[hist_idx] = q;

	uint16_t line_w1 = enc_hist_w[(global_enc_line - 0   + 128) % 128][0];
	uint16_t line_w2 = enc_hist_w[(global_enc_line - 16  + 128) % 128][1];
	uint16_t line_w3 = enc_hist_w[(global_enc_line - 32  + 128) % 128][2];
	uint16_t line_w4 = enc_hist_w[(global_enc_line - 48  + 128) % 128][3];
	uint16_t line_w5 = enc_hist_w[(global_enc_line - 64  + 128) % 128][4];
	uint16_t line_w6 = enc_hist_w[(global_enc_line - 80  + 128) % 128][5];
	uint16_t line_p  = enc_hist_p[(global_enc_line - 96  + 128) % 128];
	uint16_t line_q  = enc_hist_q[(global_enc_line - 112 + 128) % 128];

	uint16_t words_to_pack[7] = {line_w1, line_w2, line_w3, line_w4, line_w5, line_w6, line_p};

	for (int w_idx=0; w_idx<7; w_idx++) for(int b=0; b<14; b++) bits[4+(w_idx*14)+b] = ((words_to_pack[w_idx]>>(13-b))&1);

	uint16_t crc = crc16_ccitt(bits, 4, 98);
	for (int b=0; b<16; b++) bits[102+b] = ((crc>>(15-b))&1);
	for (int b=0; b<14; b++) bits[118+b] = ((line_q>>(13-b))&1);

	global_enc_line++;
      }

      for (int b=0; b<BITS_PER_LINE; b++) {
	uint32_t color = bits[b] ? C_WHITE : C_BLACK;
	for (int px=0; px<BIT_WIDTH; px++) dest_row[PIXEL_OFFSET+(b*BIT_WIDTH)+px] = color;
      }
    }

    gettimeofday(&t2, NULL);
    long us = (t2.tv_sec - t1.tv_sec) * 1000000 + (t2.tv_usec - t1.tv_usec);

    if (++video_frames % 30 == 0) {
      if (use_alsa) printf("\rCPU Render Time: %ld us | Source: ALSA Line-In | VSYNC: %s      ", us, vsync_supported ? "ON" : "OFF");
      else printf("\rCPU Render Time: %ld us | Source: Sweep | Freq: %5.0f Hz | VSYNC: %s   ", us, current_freq, vsync_supported ? "ON" : "OFF");
      fflush(stdout);
    }

    // --- ABSOLUTE SOFTWARE CLOCK LOCK ---
    // Uses an absolute monotonic drift-free target time to ensure exactly 30 FPS 
    // over hours of playback without assuming the framebuffer driver supports internal VSYNCs.
    if (!vsync_supported && !use_alsa) {
      target_time.tv_usec += 33333; 
      if (target_time.tv_usec >= 1000000) {
          target_time.tv_sec += 1;
          target_time.tv_usec -= 1000000;
      }
      
      struct timeval current_time;
      gettimeofday(&current_time, NULL);
      long diff_us = (target_time.tv_sec - current_time.tv_sec) * 1000000 + (target_time.tv_usec - current_time.tv_usec);
      
      if (diff_us > 0) {
          usleep(diff_us);
      } else if (diff_us < -100000) {
          // If we fall deeply behind (e.g. frozen process), reset target to prevent hyper-speed catching up
          gettimeofday(&target_time, NULL);
      }
    }
  }
  return 0;
}
