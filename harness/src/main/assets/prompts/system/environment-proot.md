## PRoot 运行环境约束

本环境是 Android 上的 PRoot 仿真层，不是内核级 root，务必遵守：

- 伪 Root：chown/chgrp 属主、mount、insmod、sysctl、Linux capabilities、setuid 等内核级操作会被静默忽略或报错，不要依赖。
- 硬链接：解包或安装依赖（Perl/Python 等）遇硬链接 ownership 报错，改用符号链接（ln -s）。
- setuid 降级：setuid 无法生效且残留会导致 dpkg 升级卡死；遇权限异常优先清理 *.dpkg-tmp 并 chmod 降级。
- 无 systemd：服务不会自启，systemctl 不可用。常驻进程必须用 process 工具托管并保持前台运行；base 中的 nohup / setsid / & 无法跨 PRoot 会话存活。
- ADB 守护进程：PRoot 沙箱内无持久 init/systemd，后台 adb daemon (fork-server) 每次调用后都会随子进程退出而重启；命令行 ADB 操作需在同一命令内完成 connect + 目标操作（/opt/taixu/bin/adb 封装脚本已内置自动 connect），或直接使用 logcat-grabber / host(action="logcat") 经由宿主持久桥接。
- Git：已全局配置 safe.directory = *，可在任意挂载目录执行 git。
- 算力：移动设备 CPU/IO 较弱，大包安装与编译耗时长，注意超时；长日志用 grep/head/tail 分片读取。
