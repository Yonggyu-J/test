#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>

#include <bpf/libbpf.h>
#include <bpf/bpf.h>

/*
 * 간단한 예시:
 *   1) BPF 오브젝트(`execve_hook.bpf.o`) 열기
 *   2) 오브젝트 로드(bpf_object__load)
 *   3) "lsm/bprm_check_security" 섹션 프로그램 찾기
 *   4) bpf_program__attach_lsm()로 LSM 훅 부착
 *   5) 무한 대기 (로그 확인용)
 */

int main(void)
{
    struct bpf_object *obj = NULL;
    struct bpf_program *prog = NULL;
    struct bpf_link *link = NULL;
    int err;

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
        return 1;
    }

    /* 3) 섹션 "lsm/bprm_check_security"로 프로그램 찾아 attach */
    prog = bpf_object__find_program_by_title(obj, "lsm/bprm_check_security");
    if (!prog) {
        fprintf(stderr, "ERR: couldn't find program by title\n");
        return 1;
    }

    /* 4) LSM attach */
    link = bpf_program__attach_lsm(prog);
    if (libbpf_get_error(link)) {
        fprintf(stderr, "ERR: attaching LSM hook failed: %s\n",
                strerror(errno));
        link = NULL;
        return 1;
    }

    printf("LSM hook attached. Press Ctrl+C to exit.\n");

    /* 5) 무한 대기하면서 BPF 프로그램이 커널에서 동작하도록 유지 */
    while (1) {
        sleep(1);
    }

    /* 링크/오브젝트 정리 (도달 안 함) */
    if (link)
        bpf_link__destroy(link);
    if (obj)
        bpf_object__close(obj);
    return 0;
}
