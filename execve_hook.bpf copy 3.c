#include "vmlinux.h"
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_core_read.h>

#define MAX_NAME_LEN 32

SEC("lsm/bprm_check_security")
int BPF_PROG(struct linux_binprm *bprm)
{
    struct file *file;
    struct dentry *dentry;
    struct inode *inode;
    struct qstr dname;
    char name_buf[MAX_NAME_LEN] = {}; // buffer to store the name
    int ret;

    // Read bprm->file
    file = BPF_CORE_READ(bprm, file);
    if (!file) {
        bpf_printk("[LSM] Failed to get file from bprm");
        return 0;
    }
    bpf_printk("[LSM] bprm addr: %p, file addr: %p", bprm, file);

    // Read file->f_path.dentry
    dentry = BPF_CORE_READ(file, f_path.dentry);
    if (!dentry) {
        bpf_printk("[LSM] Failed to get dentry");
        return 0;
    }
    bpf_printk("[LSM] dentry address: %p", dentry);

    // Read file->f_inode
    inode = BPF_CORE_READ(file, f_inode);
    bpf_printk("[LSM] file->f_inode: %p", inode);

    // Read dentry->d_name (struct qstr)
    dname = BPF_CORE_READ(dentry, d_name);
    bpf_printk("[LSM] d_name.name: %p, d_name.len: %u", dname.name, dname.len);

    if (dname.name && dname.len > 0) {
        // Ensure the length does not exceed buffer
        if (dname.len >= MAX_NAME_LEN) {
            bpf_printk("[LSM] d_name.len (%u) >= MAX_NAME_LEN (%u), truncating", dname.len, MAX_NAME_LEN -1);
        }

        // Read the string with limited size
        ret = bpf_probe_read_kernel_str(name_buf, sizeof(name_buf), dname.name);
        if (ret < 0) {
            bpf_printk("[LSM] Failed to read d_name.name: %d", ret);
            return 0;
        }

        // Print the name
        bpf_printk("[LSM] d_name: %s", name_buf);
    } else {
        // Read dentry->d_iname
        bpf_printk("[LSM] d_name.name is NULL or d_name.len is 0, reading d_iname");

        ret = bpf_probe_read_kernel_str(name_buf, sizeof(name_buf), dentry->d_iname);
        if (ret < 0) {
            bpf_printk("[LSM] Failed to read d_iname: %d", ret);
            return 0;
        }

        // Print the name
        bpf_printk("[LSM] d_iname: %s", name_buf);
    }

    return 0;
}

/*
SEC("lsm/bprm_check_security")
int BPF_PROG(struct linux_binprm *bprm)
{
    struct file *file;
    struct dentry *dentry;
    struct qstr dname;
    char name_buf[MAX_NAME_LEN] = {}; // 버퍼 초기화

    // bprm에서 file 포인터 읽기
    file = BPF_CORE_READ(bprm, file);
    if (!file) {
        bpf_printk("Failed to get file from bprm");
        return 0;
    }
    bpf_printk("[LSM] bprm addr: %p, file addr: %p", bprm, file);

    // file->f_path.dentry 읽기
    dentry = BPF_CORE_READ(file, f_path.dentry);
    if (!dentry) {
        bpf_printk("Failed to get dentry");
        return 0;
    }
    bpf_printk("[LSM] dentry address: %p", dentry);

    // dentry->d_name (struct qstr) 읽기
    dname = BPF_CORE_READ(dentry, d_name);
    bpf_printk("[LSM] d_name.name: %p, d_name.len: %u", dname.name, dname.len);

    if (dname.name && dname.len > 0) {
        if (dname.len >= MAX_NAME_LEN) {
            bpf_printk("[LSM] d_name.len (%u) >= MAX_NAME_LEN (%u), truncating", dname.len, MAX_NAME_LEN);
        }

        int ret = bpf_probe_read_str(name_buf, sizeof(name_buf), dname.name);
        if (ret < 0) {
            bpf_printk("[LSM] Failed to read d_name.name");
            return 0;
        }

        bpf_printk("[LSM] d_name: %s", name_buf);
    } else {
        bpf_printk("[LSM] d_name.name is NULL or d_name.len is 0, reading d_iname");

        int ret = bpf_probe_read_str(name_buf, sizeof(name_buf), dentry->d_iname);
        if (ret < 0) {
            bpf_printk("[LSM] Failed to read d_iname");
            return 0;
        }

        // 읽은 파일 이름 출력
        bpf_printk("[LSM] d_iname: %s", name_buf);
    }

    return 0;
}
*/

char LICENSE[] SEC("license") = "GPL";
