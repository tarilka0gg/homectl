/* Minimal V4L2 user-control access for webcams: list and set controls. */
#include <fcntl.h>
#include <linux/videodev2.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

/* Writes "name\tid\tmin\tmax\tvalue\n" lines into buf. Returns bytes written or -1. */
int hc_v4l2_list(const char *dev, char *buf, int cap) {
    int fd = open(dev, O_RDWR);
    if (fd < 0) return -1;
    int n = 0;
    struct v4l2_queryctrl q;
    memset(&q, 0, sizeof q);
    q.id = V4L2_CTRL_FLAG_NEXT_CTRL;
    while (ioctl(fd, VIDIOC_QUERYCTRL, &q) == 0) {
        if (!(q.flags & V4L2_CTRL_FLAG_DISABLED) && (q.type == V4L2_CTRL_TYPE_INTEGER || q.type == V4L2_CTRL_TYPE_BOOLEAN)) {
            struct v4l2_control c = { .id = q.id };
            if (ioctl(fd, VIDIOC_G_CTRL, &c) == 0) {
                int w = snprintf(buf + n, cap - n, "%s\t%u\t%d\t%d\t%d\n", q.name, q.id, q.minimum, q.maximum, c.value);
                if (w < 0 || w >= cap - n) break;
                n += w;
            }
        }
        q.id |= V4L2_CTRL_FLAG_NEXT_CTRL;
    }
    close(fd);
    return n;
}

int hc_v4l2_set(const char *dev, unsigned id, int value) {
    int fd = open(dev, O_RDWR);
    if (fd < 0) return -1;
    struct v4l2_control c = { .id = id, .value = value };
    int r = ioctl(fd, VIDIOC_S_CTRL, &c);
    close(fd);
    return r;
}
