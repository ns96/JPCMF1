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
#include <pthread.h>
#include <alsa/asoundlib.h>
#include <linux/videodev2.h>
#include <signal.h>

#define WIDTH 720
#define HEIGHT 480
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

snd_pcm_t *pcm_out_handle;
int16_t ring_buffer[RING_SIZE * 2];
volatile int head = 0, tail = 0, audio_ready = 0;
FILE *wav_file = NULL;
uint32_t wav_data_length = 0;
unsigned int alsa_hardware_rate = 48000;

void write_wav_header(FILE *f, uint32_t data_len, uint32_t sample_rate) {
    fseek(f, 0, SEEK_SET);
    fwrite("RIFF", 1, 4, f);
    uint32_t chunk_size = 36 + data_len; fwrite(&chunk_size, 4, 1, f);
    fwrite("WAVEfmt ", 1, 8, f);
    uint32_t fmt_len = 16; fwrite(&fmt_len, 4, 1, f);
    uint16_t audio_fmt = 1; fwrite(&audio_fmt, 2, 1, f);
    uint16_t num_ch = 2; fwrite(&num_ch, 2, 1, f);
    fwrite(&sample_rate, 4, 1, f);
    uint32_t byte_rate = sample_rate * 4; fwrite(&byte_rate, 4, 1, f);
    uint16_t block_align = 4; fwrite(&block_align, 2, 1, f);
    uint16_t bits_per_samp = 16; fwrite(&bits_per_samp, 2, 1, f);
    fwrite("data", 1, 4, f);
    fwrite(&data_len, 4, 1, f);
}

void handle_sigint(int sig) {
    if (wav_file) {
        write_wav_header(wav_file, wav_data_length, alsa_hardware_rate);
        fclose(wav_file);
        printf("\nWAV file saved successfully.\n");
    }
    printf("\nExiting.\n");
    exit(0);
}

uint16_t crc16_ccitt(uint8_t *bits, int offset, int len) {
  uint16_t crc = 0x0000;
  for (int i = 0; i < len; i++) {
    uint8_t crc_bit = (crc & 0x8000) ? 1 : 0;
    crc <<= 1;
    if (bits[offset + i] ^ crc_bit) crc ^= 0x1021;
  }
  return crc;
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
  alsa_hardware_rate = rate; 
  return 0;
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

struct buffer { void *start; size_t length; };

int main(int argc, char *argv[]) {
  char *wav_filename = NULL;
  for(int i = 1; i < argc; i++) {
    if(strcmp(argv[i], "-f") == 0 && i + 1 < argc) wav_filename = argv[++i];
  }

  printf("Initializing Pure 48K Hardware Loopback Decoder...\n");
  if (wav_filename) {
    wav_file = fopen(wav_filename, "wb");
    write_wav_header(wav_file, 0, alsa_hardware_rate);
    signal(SIGINT, handle_sigint);
  }

  if (init_alsa_playback() < 0) return -1;
  pthread_t pb_tid;
  pthread_create(&pb_tid, NULL, playback_thread, NULL);

  int v_fd = open("/dev/video2", O_RDWR | O_NONBLOCK, 0);
  if (v_fd < 0) return -1;

  struct v4l2_format fmt = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE };
  fmt.fmt.pix.width = WIDTH; fmt.fmt.pix.height = HEIGHT;
  fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_NV12;
  fmt.fmt.pix.field = V4L2_FIELD_NONE;
  ioctl(v_fd, VIDIOC_S_FMT, &fmt);

  struct v4l2_requestbuffers req = { .count = 4, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
  ioctl(v_fd, VIDIOC_REQBUFS, &req);

  struct buffer bufs[4];
  for (int i = 0; i < req.count; i++) {
    struct v4l2_buffer buf = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP, .index = i };
    ioctl(v_fd, VIDIOC_QUERYBUF, &buf);
    bufs[i].length = buf.length;
    bufs[i].start = mmap(NULL, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, v_fd, buf.m.offset);
    ioctl(v_fd, VIDIOC_QBUF, &buf);
  }
  
  int type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
  ioctl(v_fd, VIDIOC_STREAMON, &type);

  int search_shifts[81];
  search_shifts[0] = 0;
  for(int i=1; i<=40; i++) { search_shifts[i*2 - 1] = i; search_shifts[i*2] = -i; }

  int16_t de_jitter_buffer[16][AUDIO_LINES * 8];
  int de_jitter_valid[16][AUDIO_LINES];
  memset(de_jitter_valid, 0, sizeof(de_jitter_valid));
  int current_playback_frame = -1;
  int max_frame_id_seen = -1;
  char extracted_metadata[17] = "NO_METADATA     ";
  
  int video_frames = 0;
  long total_crc_errors = 0, total_lines = 0;
  short hold_l = 0, hold_r = 0;

  while (1) {
    fd_set fds; FD_ZERO(&fds); FD_SET(v_fd, &fds);
    struct timeval tv = { .tv_sec = 2, .tv_usec = 0 };
    if (select(v_fd + 1, &fds, NULL, NULL, &tv) <= 0) continue;

    struct v4l2_buffer v_buf = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
    while (ioctl(v_fd, VIDIOC_DQBUF, &v_buf) == 0) {
      unsigned char *src_y = (unsigned char *)bufs[v_buf.index].start;
      
      int meta_found = 0;
      for (int y = 20; y <= 29 && !meta_found; y++) {
        unsigned char *row = src_y + (y * WIDTH);
        int min_val = 255, max_val = 0;
        for (int x = PIXEL_OFFSET; x < PIXEL_OFFSET + (BITS_PER_LINE * BIT_WIDTH); x++) {
          if (row[x] < min_val) min_val = row[x];
          if (row[x] > max_val) max_val = row[x];
        }
        if (max_val - min_val < 30) continue;
        int dynamic_thresh = min_val + ((max_val - min_val) / 2);

        uint8_t bits[BITS_PER_LINE];
        for (int s = 0; s < 81 && !meta_found; s++) {
          int shift = search_shifts[s];
          if (shift < -40 || shift > 40) continue;
          for (int b = 0; b < BITS_PER_LINE; b++) {
            int px_x = PIXEL_OFFSET + (b * BIT_WIDTH) + (BIT_WIDTH / 2) + shift;
            if (px_x < 0) px_x = 0; else if (px_x >= WIDTH) px_x = WIDTH - 1;
            bits[b] = (row[px_x] > dynamic_thresh) ? 1 : 0;
          }
          if (bits[0]==1 && bits[1]==0 && bits[2]==1 && bits[3]==0) {
            uint16_t read_crc = 0;
            for (int b=0; b<16; b++) read_crc |= (bits[136+b]<<(15-b));
            if (crc16_ccitt(bits, 4, 132) == read_crc) {
              meta_found = 1;
              for (int c=0; c<16; c++) {
                char ch = 0;
                for (int b=0; b<8; b++) ch |= (bits[8+(c*8)+b] << (7-b));
                extracted_metadata[c] = ch;
              }
              extracted_metadata[16] = '\0';
            }
          }
        }
      }

      int is_frame_locked[2] = {0, 0};
      int frame_locked_shift[2] = {0, 0};

      for (int line_idx = 0; line_idx < AUDIO_LINES; line_idx++) {
        int y = VBLANK_LINES + line_idx;
        int field = y % 2;
        total_lines++;
        int valid_line = 0;

        unsigned char *row = src_y + (y * WIDTH);
        int min_val = 255, max_val = 0;
        for (int x = PIXEL_OFFSET; x < PIXEL_OFFSET + (BITS_PER_LINE * BIT_WIDTH); x++) {
          if (row[x] < min_val) min_val = row[x];
          if (row[x] > max_val) max_val = row[x];
        }
        if (max_val - min_val < 30) continue;
        int dynamic_thresh = min_val + ((max_val - min_val) / 2);

        uint8_t bits[BITS_PER_LINE];
	int shifts_to_try = is_frame_locked[field] ? 5 : 81;
	for (int s = 0; s < shifts_to_try && !valid_line; s++) {
	  int shift = is_frame_locked[field] ? (frame_locked_shift[field] + search_shifts[s]) : search_shifts[s];
          if (shift < -40 || shift > 40) continue;
	  
	  for (int b = 0; b < BITS_PER_LINE; b++) {
	    int px_x = PIXEL_OFFSET + (b * BIT_WIDTH) + (BIT_WIDTH / 2) + shift;
	    if (px_x < 0) px_x = 0; else if (px_x >= WIDTH) px_x = WIDTH - 1;
	    bits[b] = (row[px_x] > dynamic_thresh) ? 1 : 0;
	  }

          if (bits[0]==1 && bits[1]==0 && bits[2]==1 && bits[3]==0) {
            uint16_t read_crc = 0;
            for (int b=0; b<16; b++) read_crc |= (bits[136+b]<<(15-b));
            uint16_t calc_crc = crc16_ccitt(bits, 4, 132);

            if (calc_crc == read_crc) {
              valid_line = 1;
              is_frame_locked[field] = 1;
              frame_locked_shift[field] = shift;

              int frame_id = (bits[4]<<3) | (bits[5]<<2) | (bits[6]<<1) | bits[7];
              if (max_frame_id_seen == -1) max_frame_id_seen = frame_id;
              else {
                  int diff = frame_id - (max_frame_id_seen % 16);
                  if (diff > 0 && diff < 8) max_frame_id_seen += diff;
                  else if (diff < -8) max_frame_id_seen += (diff + 16);
              }

              for (int w_idx=0; w_idx<8; w_idx++) {
                uint16_t val = 0;
                for (int b=0; b<16; b++) val |= (bits[8+(w_idx*16)+b]<<(15-b));
                de_jitter_buffer[frame_id][(line_idx * 8) + w_idx] = (int16_t)val;
              }
              de_jitter_valid[frame_id][line_idx] = 1;
            }
          }
        }
        
        if (!valid_line) {
          total_crc_errors++;
          is_frame_locked[field] = 0;
        }
      }

      if (max_frame_id_seen != -1) {
        if (current_playback_frame == -1) current_playback_frame = max_frame_id_seen - 2;
        
        while (current_playback_frame + 2 <= max_frame_id_seen) {
           int p_id = current_playback_frame % 16;
           if (p_id < 0) p_id += 16;

           // Error Concealment Interpolation
           for (int line_idx = 0; line_idx < AUDIO_LINES; line_idx++) {
             if (!de_jitter_valid[p_id][line_idx]) {
               int next_valid = -1;
               for (int search = line_idx + 1; search < AUDIO_LINES; search++) {
                 if (de_jitter_valid[p_id][search]) { next_valid = search; break; }
               }
               int missing_lines = (next_valid != -1 ? next_valid : AUDIO_LINES) - line_idx;
               int end_l = (next_valid != -1) ? de_jitter_buffer[p_id][(next_valid * 8) + 6] : hold_l;
               int end_r = (next_valid != -1) ? de_jitter_buffer[p_id][(next_valid * 8) + 7] : hold_r;

               for (int miss = 0; missing_lines > 0 && miss < missing_lines; miss++) {
                 double frac = (double)(miss + 1) / (missing_lines + 1);
                 int16_t l_fill = hold_l + (int16_t)((end_l - hold_l) * frac);
                 int16_t r_fill = hold_r + (int16_t)((end_r - hold_r) * frac);
                 for(int w_pair=0; w_pair<4; w_pair++) {
                   de_jitter_buffer[p_id][((line_idx + miss) * 8) + (w_pair*2)] = l_fill;
                   de_jitter_buffer[p_id][((line_idx + miss) * 8) + (w_pair*2) + 1] = r_fill;
                 }
               }
               line_idx += missing_lines - 1; 
             } else {
               hold_l = de_jitter_buffer[p_id][(line_idx * 8) + 6];
               hold_r = de_jitter_buffer[p_id][(line_idx * 8) + 7];
             }
           }

           for (int i = 0; i < VIDEO_CHUNK * 2; i++) {
             ring_buffer[head] = de_jitter_buffer[p_id][i];
             head = (head + 1) % (RING_SIZE * 2);
           }

           if (wav_file) {
             fwrite(de_jitter_buffer[p_id], sizeof(int16_t), VIDEO_CHUNK * 2, wav_file);
             wav_data_length += VIDEO_CHUNK * 4;
           }

           memset(de_jitter_valid[p_id], 0, sizeof(de_jitter_valid[p_id]));
           current_playback_frame++;
        }
      }

      if (!audio_ready) {
        int fill = (head - tail + (RING_SIZE * 2)) % (RING_SIZE * 2);
        if (fill >= (ALSA_PERIOD * 16)) audio_ready = 1;
      }

      if (++video_frames % 30 == 0) {
        double error_rate = total_lines > 0 ? ((double)total_crc_errors / total_lines) * 100.0 : 0.0;
        printf("\rFrames: %d | CRC Err: %lu (%.2f%%) | Shift Even: %d Odd: %d   \nMetadata: [%s]\033[A", 
            video_frames, total_crc_errors, error_rate, frame_locked_shift[0], frame_locked_shift[1], extracted_metadata);
        fflush(stdout);
      }

      ioctl(v_fd, VIDIOC_QBUF, &v_buf);
    }
  }
}
