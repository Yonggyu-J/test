// execve_hook_user.c
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

// 이벤트 구조체 정의 (eBPF 프로그램과 동일하게)
struct event_t {
    __u32 uid;
    __u32 pid;
    char comm[16];
    char filename[256];
};

// 전역 변수로 무한 루프 종료 제어
static volatile bool exiting = false;

// 시그널 핸들러
static void handle_signal(int sig) {
    exiting = true;
}

// 이벤트 처리 콜백 함수
static void handle_event(void *ctx, int cpu, void *data, __u32 data_sz) {
    struct event_t *event = (struct event_t *)data;
    printf("PID: %u, UID: %u, Comm: %s, Filename: %s\n",
           event->pid, event->uid, event->comm, event->filename);
}

// 에러 처리 콜백 함수 (필요시 구현)
static void handle_lost_events(void *ctx, int cpu, __u64 lost_cnt) {
    fprintf(stderr, "Lost %llu events on CPU #%d\n", lost_cnt, cpu);
}

int main(void)
{
    struct bpf_object *obj = NULL;
    struct bpf_program *prog = NULL;
    struct bpf_link *link = NULL;
    int err;
    int map_fd;
    struct perf_buffer *pb = NULL;
    struct perf_buffer_opts pb_opts = {};

    // 시그널 핸들러 등록
    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);

    /* 1) BPF 오브젝트 열기 */
    obj = bpf_object__open_file("execve_hook.bpf.o", NULL);
    if (libbpf_get_error(obj)) {
        fprintf(stderr, "ERR: opening BPF object file failed: %s\n",
                strerror(errno));
        return 1;
    }

    /* 2) 오브젝트 로드 */
    err = bpf_object__load(obj);
    if (err) {
        fprintf(stderr, "ERR: loading BPF object failed: %s\n",
                strerror(errno));
        goto cleanup;
    }

    /* 3) 섹션 "lsm/bprm_check_security"로 프로그램 찾아 attach */
    prog = bpf_object__find_program_by_title(obj, "lsm/bprm_check_security");
    if (!prog) {
        fprintf(stderr, "ERR: couldn't find program by title\n");
        goto cleanup;
    }

    /* 4) LSM attach */
    link = bpf_program__attach_lsm(prog);
    if (libbpf_get_error(link)) {
        fprintf(stderr, "ERR: attaching LSM hook failed: %s\n",
                strerror(errno));
        link = NULL;
        goto cleanup;
    }

    /* 5) Perf 이벤트 맵 찾기 */
    struct bpf_map *map = bpf_object__find_map_by_name(obj, "events");
    if (!map) {
        fprintf(stderr, "ERR: finding map 'events' failed\n");
        goto cleanup;
    }

    map_fd = bpf_map__fd(map);
    if (map_fd < 0) {
        fprintf(stderr, "ERR: getting map fd failed: %s\n", strerror(errno));
        goto cleanup;
    }

    /* 6) Perf Buffer 옵션 설정 */
    pb_opts.sample_cb = handle_event;
    pb_opts.lost_cb = handle_lost_events;

    /* 7) Perf Buffer 생성 */
    pb = perf_buffer__new(map_fd, 8, &pb_opts);  // 인자 수를 3개로 수정
    if (libbpf_get_error(pb)) {
        fprintf(stderr, "ERR: creating perf buffer failed\n");
        pb = NULL;
        goto cleanup;
    }

    printf("LSM hook attached. Listening for events... Press Ctrl+C to exit.\n");

    /* 8) 이벤트 폴링 루프 */
    while (!exiting) {
        err = perf_buffer__poll(pb, 100 /* ms */);
        if (err == -EINTR) {
            // 시그널에 의해 인터럽트됨
            break;
        }
        if (err < 0) {
            fprintf(stderr, "ERR: perf buffer poll failed: %d\n", err);
            break;
        }
        // 이벤트가 처리됨
    }

cleanup:
    /* 정리 */
    if (pb)
        perf_buffer__free(pb);
    if (link)
        bpf_link__destroy(link);
    if (obj)
        bpf_object__close(obj);

    printf("Exiting.\n");
    return err != 0;
}
