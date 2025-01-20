#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <bpf/libbpf.h>
#include <bpf/bpf.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <pwd.h>

#define MAX_ARGS    10
#define MAX_ARG_LEN 16
#define MAX_ACC_LIST 10

struct event_t {
    __u32 pid;
    __u32 uid;
    char status;
    char comm[16];
    char filename[16];
};

struct config_t {
    __u32 whitelist_count;
    __u32 whitelist[MAX_ACC_LIST];
    __u32 arg_count;
    char args[MAX_ARGS][MAX_ARG_LEN];
};

static volatile bool exiting = false;

static void handle_signal(int sig) {
    exiting = true;
}

static void handle_event(void *ctx, int cpu, void *data, __u32 data_sz) {
    const struct event_t *event = (const struct event_t *)data;
    printf("ST:%d PID:%u UID:%u COMM:%s FILE:%s\n",
           event->status, event->pid, event->uid,
           event->comm, event->filename);
}

static void handle_lost_events(void *ctx, int cpu, __u64 lost_cnt) {
    fprintf(stderr, "Lost %llu events on CPU #%d\n", lost_cnt, cpu);
}

static int parse_bracket_str(const char *bracket_str, char buf_array[][MAX_ARG_LEN], int elem_max, int buf_len) {
    size_t slen = strlen(bracket_str);
    if (slen < 2 || bracket_str[0] != '[' || bracket_str[slen - 1] != ']')
        return 0;

    char tmp[256] = {};
    size_t copy_len = (slen - 2 < sizeof(tmp) - 1) ? (slen - 2) : (sizeof(tmp) - 1);
    memcpy(tmp, bracket_str + 1, copy_len);

    int count = 0;
    char *tok, *saveptr;
    tok = strtok_r(tmp, ",", &saveptr);
    while (tok && count < elem_max) {
        strncpy(buf_array[count], tok, buf_len - 1);
        buf_array[count][buf_len - 1] = '\0';
        count++;
        tok = strtok_r(NULL, ",", &saveptr);
    }
    return count;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "Usage: %s <eBPF_object_file.o> [whitelist_users] [block_file]\n", argv[0]);
        return EXIT_FAILURE;
    }

    const char *ebpf_obj_file = argv[1];
    const char *whitelist_str = argv[2];
    const char *args_str      = argv[3];

    size_t len = strlen(ebpf_obj_file);
    if (len < 3 || strcmp(ebpf_obj_file + (len - 2), ".o") != 0) {
        fprintf(stderr, "ERR: The first argument must be a .o file\n");
        return EXIT_FAILURE;
    }

    char user_buf[MAX_ACC_LIST][MAX_ARG_LEN] = {};
    int user_count = parse_bracket_str(whitelist_str, user_buf, MAX_ACC_LIST, MAX_ARG_LEN);

    char file_buf[MAX_ARGS][MAX_ARG_LEN] = {};
    int file_count = parse_bracket_str(args_str, file_buf, MAX_ARGS, MAX_ARG_LEN);
    if(file_count < 1) {
        fprintf(stderr, "ERR: block_file is not specified.\n");
        return EXIT_FAILURE;      
    }

    /* config_t 설정 */
    struct config_t cfg;
    memset(&cfg, 0, sizeof(cfg));

    int whitelist_count = 0;
    for (; whitelist_count < user_count; whitelist_count++) {
        struct passwd *pw = getpwnam(user_buf[whitelist_count]);
        cfg.whitelist[whitelist_count] = pw ? pw->pw_uid : 0;
    }
    cfg.whitelist_count = whitelist_count;

    cfg.arg_count = file_count;
    for (int i = 0; i < file_count; i++) {
        strncpy(cfg.args[i], file_buf[i], MAX_ARG_LEN - 1);
        cfg.args[i][MAX_ARG_LEN - 1] = '\0';
    }

    /* eBPF 프로그램 로드 및 attach */
    struct bpf_object *obj = NULL;
    struct bpf_link *link  = NULL;
    struct perf_buffer *pb = NULL;
    struct perf_buffer_opts pb_opts = {};
    int events_map_fd, config_map_fd, err = 0;

    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);

    obj = bpf_object__open_file(ebpf_obj_file, NULL);
    if (libbpf_get_error(obj)) {
        fprintf(stderr, "ERR: opening BPF object '%s'\n", ebpf_obj_file);
        return EXIT_FAILURE;
    }

    err = bpf_object__load(obj);
    if (err) {
        fprintf(stderr, "ERR: loading BPF object\n");
        goto cleanup;
    }

    struct bpf_program *prog = bpf_object__find_program_by_title(obj, "lsm/bprm_check_security");
    if (!prog) {
        fprintf(stderr, "ERR: no program 'lsm/bprm_check_security'\n");
        err = -ENOENT;
        goto cleanup;
    }

    link = bpf_program__attach_lsm(prog);
    if (libbpf_get_error(link)) {
        fprintf(stderr, "ERR: attaching LSM hook\n");
        link = NULL;
        err = -EINVAL;
        goto cleanup;
    }

    struct bpf_map *map = bpf_object__find_map_by_name(obj, "events");
    if (!map) {
        fprintf(stderr, "ERR: map 'events' not found\n");
        err = -ENOENT;
        goto cleanup;
    }
    events_map_fd = bpf_map__fd(map);

    map = bpf_object__find_map_by_name(obj, "config_map");
    if (!map) {
        fprintf(stderr, "ERR: map 'config_map' not found\n");
        err = -ENOENT;
        goto cleanup;
    }
    config_map_fd = bpf_map__fd(map);

    {
        __u32 key = 0;
        err = bpf_map_update_elem(config_map_fd, &key, &cfg, BPF_ANY);
        if (err) {
            fprintf(stderr, "ERR: bpf_map_update_elem() failed\n");
            goto cleanup;
        }
    }

    pb_opts.sample_cb = handle_event;
    pb_opts.lost_cb   = handle_lost_events;
    pb = perf_buffer__new(events_map_fd, 8, &pb_opts);
    if (libbpf_get_error(pb)) {
        fprintf(stderr, "ERR: perf_buffer__new()\n");
        pb = NULL;
        err = -EINVAL;
        goto cleanup;
    }

    printf("Running eBPF program '%s'\n", ebpf_obj_file);

    while (!exiting) {
        err = perf_buffer__poll(pb, 100);
        if (err == -EINTR)
            break;
        if (err < 0) {
            fprintf(stderr, "ERR: perf_buffer__poll returned %d\n", err);
            break;
        }
    }

cleanup:
    perf_buffer__free(pb);
    bpf_link__destroy(link);
    bpf_object__close(obj);

    return err ? EXIT_FAILURE : EXIT_SUCCESS;
}
