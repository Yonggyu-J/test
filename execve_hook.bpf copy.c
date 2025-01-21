#include "vmlinux.h"
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_core_read.h>

char LICENSE[] SEC("license") = "GPL";

SEC("lsm/bprm_committing_creds")
int BPF_PROG(struct linux_binprm *bprm)
{
    char filename[256] = {0};  // 파일 이름 버퍼 초기화
    __u32 uid_val = 0, euid_val = 0;

    // cred 포인터를 안전하게 읽기
    const struct cred *cred = BPF_CORE_READ(bprm, cred);

    if (cred) {
        // cred 구조체에서 uid와 euid 값을 안전하게 읽기
        uid_val = BPF_CORE_READ(cred, uid.val);
        euid_val = BPF_CORE_READ(cred, euid.val);

        // 디버깅을 위해 UID, EUID 값 출력
        bpf_printk("DEBUG: UID: %u, EUID: %u\n", uid_val, euid_val);

        if (uid_val != euid_val) {
            bpf_printk("Privilege escalation detected! UID: %u, EUID: %u\n", uid_val, euid_val);
        }
    } else {
        bpf_printk("Failed to read cred pointer\n");
    }

    const char *file_name_ptr = BPF_CORE_READ(bprm, filename);
    if (file_name_ptr) {
        int ret = bpf_probe_read_str(filename, sizeof(filename), file_name_ptr);
        if (ret > 0) {
            bpf_printk("Opened file name: %s\n", filename);
        } else {
            bpf_printk("Opened file name: <failed to read>\n");
        }
    } else {
        bpf_printk("Opened file name: <null pointer>\n");
    }

    return 0;
}
