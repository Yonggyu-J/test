#include "vmlinux.h"
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>
#include <bpf/bpf_core_read.h>

#define MAX_ARGS        10
#define MAX_ARG_LEN     16
#define MAX_ACC_LIST    10

// Struct 44 Byte
struct event_t {
    __u32 pid;
    __u32 uid;
    char status;
    char comm[16];
    char filename[16];
};

// Struct 208 Byte
struct config_t {
    __u32 whitelist_count;
    __u32 whitelist[MAX_ACC_LIST];
    __u32 arg_count;
    char args[MAX_ARGS][MAX_ARG_LEN];
};

struct {
    __uint(type, BPF_MAP_TYPE_PERF_EVENT_ARRAY);
    __uint(key_size, sizeof(u32));
    __uint(value_size, sizeof(u32));
} events SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(max_entries, 1);
    __uint(key_size, sizeof(u32));
    __uint(value_size, sizeof(struct config_t));
} config_map SEC(".maps");

static __always_inline int compare_str(const char *s1, const char *s2)
{
    for (int i = 0; i < MAX_ARG_LEN; i++) {
        if (s1[i] != s2[i])
            return 0;
        if (s1[i] == '\0')
            break;
    }
    return 1;
}

// Local var 84 Byte, total Used Stack Memory is 336 Byte
SEC("lsm/bprm_check_security")
int BPF_PROG(lsm_bprm_check_security, struct linux_binprm *bprm)
{
    struct event_t event = {};
    struct file *file = BPF_CORE_READ(bprm, file);
    struct qstr dname = BPF_CORE_READ(file, f_path.dentry, d_name);

    bpf_probe_read_kernel(event.filename, sizeof(event.filename), dname.name);
    event.uid = bpf_get_current_uid_gid();
    event.pid = bpf_get_current_pid_tgid() >> 32;
    bpf_get_current_comm(&event.comm, sizeof(event.comm));
    event.status = 1;

    __u32 key = 0;
    struct config_t *cfg = bpf_map_lookup_elem(&config_map, &key);
    if (cfg) {
        for (int i = 0; i < cfg->arg_count && i < MAX_ARGS; i++) {
            if (compare_str(cfg->args[i], event.filename)) {
                int found = 0;
                for (int j = 0; j < cfg->whitelist_count && j < MAX_ACC_LIST; j++) {
                    if (cfg->whitelist[j] == event.uid) {
                        found = 1;
                        break;
                    }
                }
                if (!found) {
                    event.status = -1;
                    bpf_perf_event_output(ctx, &events, BPF_F_CURRENT_CPU, &event, sizeof(event));
                    return -1;
                }
            }
        }
    }
    bpf_perf_event_output(ctx, &events, BPF_F_CURRENT_CPU, &event, sizeof(event));
    return 0;
}

char _license[] SEC("license") = "GPL";
