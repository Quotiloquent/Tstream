#define _GNU_SOURCE
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <linux/falloc.h>

/* Frees the disk blocks of [offset, offset+length) but keeps the file size (sparse hole). Returns 0 or -errno. */
JNIEXPORT jint JNICALL
Java_com_noapmat_tsream_cache_HolePuncher_punch(JNIEnv *env, jclass clazz, jstring path,
                                                       jlong offset, jlong length) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p == NULL) return -ENOMEM;
    int fd = open(p, O_RDWR | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (fd < 0) return -errno;
    int r = fallocate(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, (off_t) offset, (off_t) length);
    int result = (r == 0) ? 0 : -errno;
    close(fd);
    return result;
}
