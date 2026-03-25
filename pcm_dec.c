#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/videodev2.h>
#include <sys/time.h>
#include <alsa/asoundlib.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>

/* Video Settings */
#define WIDTH 720
#define HEIGHT 480
#define V_DEV "/dev/video2"

/* STC-007 Constants */
#define BITS_PER_LINE 137
#define BIT_WIDTH 5
#define PIXEL_OFFSET ((WIDTH - (BITS_PER_LINE * BIT_WIDTH)) / 2) + 1
#define VBLANK_LINES 64
#define AUDIO_LINES 352
#define SAMPLES_PER_LINE 3

/* Audio Engine */
#define PCM_DEVICE "hw:0,0"
#define PERIOD_SIZE 1024
#define RING_SIZE (PERIOD_SIZE * 32)

snd_pcm_t *pcm_out_handle;
struct buffer { void *start; size_t length; };

int16_t ring_buffer[RING_SIZE * 2];
volatile int head = 0, tail = 0, audio_ready = 0;
unsigned int alsa_hardware_rate = 48000;

FILE *wav_file = NULL;
uint32_t wav_data_length = 0;

void write_wav_header(FILE *f, uint32_t data_len, uint32_t sample_rate) {
    uint32_t riff_len = 36 + data_len;
    rewind(f);
    fwrite("RIFF", 1, 4, f);
    fwrite(&riff_len, 4, 1, f);
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

int init_alsa_playback() {
  snd_pcm_hw_params_t *params;
  if (snd_pcm_open(&pcm_out_handle, PCM_DEVICE, SND_PCM_STREAM_PLAYBACK, 0) < 0) return -1;
  snd_pcm_hw_params_alloca(&params);
  snd_pcm_hw_params_any(pcm_out_handle, params);
  snd_pcm_hw_params_set_access(pcm_out_handle, params, SND_PCM_ACCESS_RW_INTERLEAVED);
  snd_pcm_hw_params_set_format(pcm_out_handle, params, SND_PCM_FORMAT_S16_LE);
  snd_pcm_hw_params_set_channels(pcm_out_handle, params, 2);
  snd_pcm_hw_params_set_rate_near(pcm_out_handle, params, &alsa_hardware_rate, 0);
  snd_pcm_hw_params_set_period_size(pcm_out_handle, params, PERIOD_SIZE, 0);
  snd_pcm_hw_params_set_buffer_size(pcm_out_handle, params, PERIOD_SIZE * 8);
  if (snd_pcm_hw_params(pcm_out_handle, params) < 0) return -1;
  snd_pcm_prepare(pcm_out_handle);
  return 0;
}

void *playback_thread(void *arg) {
  struct sched_param param = { .sched_priority = 95 };
  pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);
  int16_t out_tmp[PERIOD_SIZE * 2];

  while (1) {
    if (!audio_ready) { usleep(10000); continue; }
    int avail = (head - tail + (RING_SIZE * 2)) % (RING_SIZE * 2);
    if (avail < PERIOD_SIZE * 2) { usleep(1000); continue; }
    for (int i = 0; i < PERIOD_SIZE * 2; i++) {
      out_tmp[i] = ring_buffer[tail]; tail = (tail + 1) % (RING_SIZE * 2);
    }
    if (snd_pcm_writei(pcm_out_handle, out_tmp, PERIOD_SIZE) < 0) snd_pcm_prepare(pcm_out_handle);
  }
  return NULL;
}

int main(int argc, char *argv[]) {
  char *wav_filename = NULL;
  for(int i = 1; i < argc; i++) {
    if(strcmp(argv[i], "-f") == 0 && i + 1 < argc) {
      wav_filename = argv[++i];
    }
  }

  printf("Initializing Hardware for PLL-Synchronized Loopback...\n");
  
  if (wav_filename) {
    wav_file = fopen(wav_filename, "wb");
    if (wav_file) {
      write_wav_header(wav_file, 0, alsa_hardware_rate);
      signal(SIGINT, handle_sigint);
      printf("Recording output to WAV file: %s\n", wav_filename);
    }
  }

  int v_fd = open(V_DEV, O_RDWR);
  struct v4l2_format fmt = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .fmt.pix = { .width = WIDTH, .height = HEIGHT, .pixelformat = V4L2_PIX_FMT_NV12 } };
  ioctl(v_fd, VIDIOC_S_FMT, &fmt);

  struct v4l2_requestbuffers req = { .count = 4, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };
  ioctl(v_fd, VIDIOC_REQBUFS, &req);

  struct buffer *bufs = calloc(4, sizeof(*bufs));
  for (int i = 0; i < 4; i++) {
    struct v4l2_buffer b = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP, .index = i };
    ioctl(v_fd, VIDIOC_QUERYBUF, &b);
    bufs[i].start = mmap(NULL, b.length, PROT_READ|PROT_WRITE, MAP_SHARED, v_fd, b.m.offset);
    ioctl(v_fd, VIDIOC_QBUF, &b);
  }

  if (init_alsa_playback() < 0) return 1;

  pthread_t tid;
  pthread_create(&tid, NULL, playback_thread, NULL);
  ioctl(v_fd, VIDIOC_STREAMON, (enum v4l2_buf_type[]){V4L2_BUF_TYPE_VIDEO_CAPTURE});
  struct v4l2_buffer v_buf = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP };

  short delay_matrix[4096][8];
  for(int i=0; i<4096; i++) for(int j=0; j<8; j++) delay_matrix[i][j] = -1;
  long global_line_idx = 0;

  int stats_crc_errors = 0, stats_corrections = 0, stats_dropped = 0, video_frames = 0;
  unsigned long total_crc_errors = 0, total_lines = 0;

  int search_shifts[81];
  search_shifts[0] = 0;
  for(int i = 1; i <= 40; i++) {
    search_shifts[i*2 - 1] = i; search_shifts[i*2] = -i;
  }

  int alsa_frames_needed = AUDIO_LINES * SAMPLES_PER_LINE; // 1056
  int16_t curr_decoded_audio[alsa_frames_needed * 2];
  int16_t prev_decoded_audio[alsa_frames_needed * 2];
  int16_t dec_tail[2][2] = {{0}};
  int first_frame = 1;

  int16_t hold_l = 0, hold_r = 0;
  double current_phase = 0.0;

  printf("Decoder Active. Dynamic Resampling & Smooth Software PLL Enabled.\n");

  while (ioctl(v_fd, VIDIOC_DQBUF, &v_buf) == 0) {
    unsigned char *src_y = (unsigned char *)bufs[v_buf.index].start;
    int decoded_idx = 0;

    int is_frame_locked = 0;
    int frame_locked_shift = 0;

    for (int y = VBLANK_LINES + 1; y <= VBLANK_LINES + AUDIO_LINES; y++) {
	total_lines++;
	int valid_line = 0;
	int matrix_idx = global_line_idx % 4096;

	int min_val = 255, max_val = 0;
	for (int x = PIXEL_OFFSET + 10; x < WIDTH - 20; x+=5) {
	  int v = src_y[y * WIDTH + x];
	  if (v < min_val) min_val = v; if (v > max_val) max_val = v;
	}

	if (max_val - min_val < 30) {
	  stats_crc_errors++; total_crc_errors++;
	  for(int w=0; w<8; w++) delay_matrix[matrix_idx][w] = -1;
	  global_line_idx++; continue;
	}
	int dynamic_thresh = min_val + ((max_val - min_val) / 2);

	uint8_t bits[BITS_PER_LINE];
	int shifts_to_try = is_frame_locked ? 5 : 81;
	for (int s = 0; s < shifts_to_try && !valid_line; s++) {
	  int shift = is_frame_locked ? (frame_locked_shift + search_shifts[s]) : search_shifts[s];
          if (shift < -40 || shift > 40) continue;
	  for (int b = 0; b < BITS_PER_LINE; b++) {
	    int px_x = PIXEL_OFFSET + (b * BIT_WIDTH) + (BIT_WIDTH / 2) + shift;
	    if (px_x < 0) px_x = 0; else if (px_x >= WIDTH) px_x = WIDTH - 1;
	    bits[b] = (src_y[y * WIDTH + px_x] >= dynamic_thresh) ? 1 : 0;
	  }

	  if (bits[0]==0 && bits[1]==1 && bits[2]==0 && bits[3]==1) {
	    uint16_t calc_crc = crc16_ccitt(bits, 4, 98);
	    uint16_t read_crc = 0;
	    for (int b = 0; b < 16; b++) read_crc |= (bits[102 + b] << (15 - b));

	    if (calc_crc == read_crc) {
	      for (int w = 0; w < 7; w++) {
		short val = 0;
		for (int b = 0; b < 14; b++) val |= (bits[4 + (w * 14) + b] << (13 - b));
		delay_matrix[matrix_idx][w] = val;
	      }
	      short qVal = 0;
	      for (int b = 0; b < 14; b++) qVal |= (bits[118 + b] << (13 - b));
	      delay_matrix[matrix_idx][7] = qVal;
	      valid_line = 1;
	      if (!is_frame_locked) { is_frame_locked = 1; frame_locked_shift = shift; }
	    }
	  }
	}

	if (!valid_line) {
	  stats_crc_errors++; total_crc_errors++;
	  for(int w=0; w<8; w++) delay_matrix[matrix_idx][w] = -1;
	  is_frame_locked = 0;
	}

	// DELAY MATRIX & ERROR CORRECTION
	if (global_line_idx >= 112) {
	  short w1 = delay_matrix[(global_line_idx - 112) % 4096][0];
	  short w2 = delay_matrix[(global_line_idx - 96)  % 4096][1];
	  short w3 = delay_matrix[(global_line_idx - 80)  % 4096][2];
	  short w4 = delay_matrix[(global_line_idx - 64)  % 4096][3];
	  short w5 = delay_matrix[(global_line_idx - 48)  % 4096][4];
	  short w6 = delay_matrix[(global_line_idx - 32)  % 4096][5];
	  short p  = delay_matrix[(global_line_idx - 16)  % 4096][6];
	  short q  = delay_matrix[(global_line_idx)       % 4096][7];

	  int missing = 0;
	  if (w1 == -1) missing++; if (w2 == -1) missing++;
	  if (w3 == -1) missing++; if (w4 == -1) missing++;
	  if (w5 == -1) missing++; if (w6 == -1) missing++;

	  if (missing == 1 && p != -1) {
	    if (w1 == -1) w1 = w2 ^ w3 ^ w4 ^ w5 ^ w6 ^ p;
	    else if (w2 == -1) w2 = w1 ^ w3 ^ w4 ^ w5 ^ w6 ^ p;
	    else if (w3 == -1) w3 = w1 ^ w2 ^ w4 ^ w5 ^ w6 ^ p;
	    else if (w4 == -1) w4 = w1 ^ w2 ^ w3 ^ w5 ^ w6 ^ p;
	    else if (w5 == -1) w5 = w1 ^ w2 ^ w3 ^ w4 ^ w6 ^ p;
	    else if (w6 == -1) w6 = w1 ^ w2 ^ w3 ^ w4 ^ w5 ^ p;
	    stats_corrections++; missing = 0;
	  }

	    int l1_e = (q != -1) ? (q >> 12) & 3 : 0; int r1_e = (q != -1) ? (q >> 10) & 3 : 0;
	    int l2_e = (q != -1) ? (q >> 8)  & 3 : 0; int r2_e = (q != -1) ? (q >> 6)  & 3 : 0;
	    int l3_e = (q != -1) ? (q >> 4)  & 3 : 0; int r3_e = (q != -1) ? (q >> 2)  & 3 : 0;

	    int16_t raw_l[3], raw_r[3];
	    int l_v[3] = {w1 != -1, w3 != -1, w5 != -1};
	    int r_v[3] = {w2 != -1, w4 != -1, w6 != -1};

	    if (l_v[0]) raw_l[0] = (int16_t)(((uint16_t)w1 << 2) | (uint16_t)l1_e);
	    if (l_v[1]) raw_l[1] = (int16_t)(((uint16_t)w3 << 2) | (uint16_t)l2_e);
	    if (l_v[2]) raw_l[2] = (int16_t)(((uint16_t)w5 << 2) | (uint16_t)l3_e);

	    if (r_v[0]) raw_r[0] = (int16_t)(((uint16_t)w2 << 2) | (uint16_t)r1_e);
	    if (r_v[1]) raw_r[1] = (int16_t)(((uint16_t)w4 << 2) | (uint16_t)r2_e);
	    if (r_v[2]) raw_r[2] = (int16_t)(((uint16_t)w6 << 2) | (uint16_t)r3_e);

	    if (!l_v[0]) raw_l[0] = l_v[1] ? (hold_l + raw_l[1]) / 2 : hold_l;
	    if (!l_v[1]) raw_l[1] = l_v[2] ? (raw_l[0] + raw_l[2]) / 2 : raw_l[0];
	    if (!l_v[2]) raw_l[2] = raw_l[1];

	    if (!r_v[0]) raw_r[0] = r_v[1] ? (hold_r + raw_r[1]) / 2 : hold_r;
	    if (!r_v[1]) raw_r[1] = r_v[2] ? (raw_r[0] + raw_r[2]) / 2 : raw_r[0];
	    if (!r_v[2]) raw_r[2] = raw_r[1];

	    int16_t s[6] = {raw_l[0], raw_r[0], raw_l[1], raw_r[1], raw_l[2], raw_r[2]};

	    hold_l = s[4]; hold_r = s[5];
	    if (missing > 0) stats_dropped++;

	    if (decoded_idx < alsa_frames_needed * 2) {
	      for(int i=0; i<6; i++) curr_decoded_audio[decoded_idx++] = s[i];
	    }
	}
	global_line_idx++;
    }

    // --- DYNAMIC ASRC (SOFTWARE PLL) ---
    if (decoded_idx == alsa_frames_needed * 2) {
      if (first_frame) {
	memcpy(prev_decoded_audio, curr_decoded_audio, sizeof(curr_decoded_audio));
	dec_tail[0][0] = prev_decoded_audio[(alsa_frames_needed - 2) * 2 + 0];
	dec_tail[0][1] = prev_decoded_audio[(alsa_frames_needed - 2) * 2 + 1];
	dec_tail[1][0] = prev_decoded_audio[(alsa_frames_needed - 1) * 2 + 0];
	dec_tail[1][1] = prev_decoded_audio[(alsa_frames_needed - 1) * 2 + 1];
	first_frame = 0;
      } else {
	// Monitor the ALSA buffer to prevent starvation XRUNs
	int buffer_level = (head - tail + (RING_SIZE * 2)) % (RING_SIZE * 2);

	// Smooth PI tracking eliminates 30Hz FM intermodulation beating from ratio jumping
	static double smooth_ratio = 0.0;
        if (smooth_ratio == 0.0) smooth_ratio = (double)alsa_frames_needed / 1601.6;
	int error = buffer_level - RING_SIZE;
	smooth_ratio += error * 0.0000000005;

	// Absolute safety bounding to prevent insane clock drift
	double base_ratio = (double)alsa_frames_needed / 1601.6;
	if (smooth_ratio < base_ratio * 0.95) smooth_ratio = base_ratio * 0.95;
	if (smooth_ratio > base_ratio * 1.05) smooth_ratio = base_ratio * 1.05;

	while (current_phase < alsa_frames_needed) {
	  int idx1 = (int)floor(current_phase); 
	  double frac = current_phase - idx1;
          if (idx1 < -1) { idx1 = -1; frac = 0.0; }

#define GET_DEC_SAMP(idx, ch) \
	  ((idx) < 0 ? dec_tail[2 + (idx)][(ch)] : \
	   ((idx) < alsa_frames_needed ? prev_decoded_audio[(idx) * 2 + (ch)] : \
	     curr_decoded_audio[((idx) - alsa_frames_needed) * 2 + (ch)]))

	  int16_t out_l = cubic_interp(GET_DEC_SAMP(idx1-1,0), GET_DEC_SAMP(idx1,0), GET_DEC_SAMP(idx1+1,0), GET_DEC_SAMP(idx1+2,0), frac);
	  int16_t out_r = cubic_interp(GET_DEC_SAMP(idx1-1,1), GET_DEC_SAMP(idx1,1), GET_DEC_SAMP(idx1+1,1), GET_DEC_SAMP(idx1+2,1), frac);

	  ring_buffer[head] = out_l; head = (head + 1) % (RING_SIZE * 2);
	  ring_buffer[head] = out_r; head = (head + 1) % (RING_SIZE * 2);
	  current_phase += smooth_ratio;
	  
	  if (wav_file) {
	    int16_t sample[2] = {out_l, out_r};
	    fwrite(sample, sizeof(int16_t), 2, wav_file);
	    wav_data_length += 4;
	  }
	}
	current_phase -= alsa_frames_needed;
	dec_tail[0][0] = prev_decoded_audio[(alsa_frames_needed - 2) * 2 + 0];
	dec_tail[0][1] = prev_decoded_audio[(alsa_frames_needed - 2) * 2 + 1];
	dec_tail[1][0] = prev_decoded_audio[(alsa_frames_needed - 1) * 2 + 0];
	dec_tail[1][1] = prev_decoded_audio[(alsa_frames_needed - 1) * 2 + 1];
	memcpy(prev_decoded_audio, curr_decoded_audio, sizeof(curr_decoded_audio));
      }
      if (!audio_ready && head >= (PERIOD_SIZE * 16)) audio_ready = 1;
    }

    if (++video_frames % 30 == 0) {
      double error_rate = total_lines > 0 ? ((double)total_crc_errors / total_lines) * 100.0 : 0.0;
      printf("\rFrames: %d | Total CRC: %lu (%.2f%%) | Fixed: %d | Dropped: %d | Lock: %d   ",
	     video_frames, total_crc_errors, error_rate, stats_corrections, stats_dropped, search_shifts[0]);
      fflush(stdout);
      stats_crc_errors = 0; stats_corrections = 0; stats_dropped = 0;
    }

    ioctl(v_fd, VIDIOC_QBUF, &v_buf);
  }
  return 0;
}
