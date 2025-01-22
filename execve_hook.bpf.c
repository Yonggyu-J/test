#include "vmlinux.h"
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_core_read.h>

#define ARG_BUF_SIZE 256
#define MAX_ARGS 20

SEC("lsm/bprm_check_security")
int execve_hook(struct linux_binprm *bprm)
{
    int err;

    if (!bprm) {
        bpf_printk("execve_hook: bprm is NULL\n");
        return 0;
    }

    // 1. argc 읽기
    int argc;
    err = bpf_core_read(&argc, sizeof(argc), &bprm->argc);
    if (err) {
        bpf_printk("execve_hook: Failed to read argc\n");
        return 0;
    }
    bpf_printk("execve_hook: argc=%d\n", argc);

    if (argc <= 0 || argc > MAX_ARGS) {
        bpf_printk("execve_hook: Unexpected argc=%d\n", argc);
        return 0;
    }

    // 2. current->mm 접근
    struct task_struct *task;
    struct mm_struct *mm;

    task = (struct task_struct *)bpf_get_current_task();
    err = bpf_core_read(&mm, sizeof(mm), &task->mm);
    if (err || !mm) {
        bpf_printk("execve_hook: Failed to read current->mm\n");
        return 0;
    }

    // 3. arg_start 및 arg_end 읽기
    unsigned long arg_start, arg_end;
    err = bpf_core_read(&arg_start, sizeof(arg_start), &mm->arg_start);
    if (err) {
        bpf_printk("execve_hook: Failed to read arg_start\n");
        return 0;
    }
    err = bpf_core_read(&arg_end, sizeof(arg_end), &mm->arg_end);
    if (err) {
        bpf_printk("execve_hook: Failed to read arg_end\n");
        return 0;
    }

    // 디버깅을 위해 arg_start와 arg_end 출력
    bpf_printk("execve_hook: arg_start=0x%lx, arg_end=0x%lx\n", arg_start, arg_end);

    // 4. 인자 문자열 읽기
    char arg[ARG_BUF_SIZE];
    __u64 arg_addr = arg_start;
    int remaining = arg_end - arg_start;

    for (int i = 0; i < argc && i < MAX_ARGS; i++) {
        if (remaining <= 0) {
            bpf_printk("execve_hook: Not enough space for argument[%d]\n", i);
            break;
        }

        int len = bpf_probe_read_user_str(arg, sizeof(arg), (void *)arg_addr);
        if (len < 0) {
            bpf_printk("execve_hook: Failed to read argument[%d] (error=%d)\n", i, len);
            break;
        }

        if (arg_addr + len > arg_end || len > remaining) {
            bpf_printk("execve_hook: Argument[%d] exceeds arg_end\n", i);
            break;
        }

        bpf_printk("execve_hook: argv[%d]: %s\n", i, arg);

        arg_addr += len;
        remaining -= len;
    }

    return 0;
}

char LICENSE[] SEC("license") = "GPL";
