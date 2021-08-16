```shell script
#1. 将flink-dist下的src/main/flink-bin/conf/* 和  src/main/resources/* 的文件拷贝至 根目录下的 conf 中
#2. 编译项目，等待 n min，比较久
mvn clean install -DskipTests -Dfast
#4. Standalone 本地启动JobManager和TaskManager
# 4.1 启动JobManager
org.apache.flink.container.entrypoint.StandaloneApplicationClusterEntryPoint.StandaloneApplicationClusterEntryPoint
VM参数: -Dlog.file=./log/standalonesession.log -Dlog4j.configurationFile=./conf/log4j.properties -Dlogback.configurationFile=conf/logback.xml -classpath lib/*;;; 
程序参数: --configDir ./conf --executionMode cluster

# 4.2 启动TaskManager
org.apache.flink.runtime.taskexecutor.TaskManagerRunner
VM参数: -Dlog.file=./log/taskexecutor.log -Dlog4j.configurationFile=./conf/log4j.properties -Dlogback.configurationFile=conf/logback.xml -classpath lib/*;;; 
程序参数: --configDir ./conf --configDir ./conf -D taskmanager.memory.framework.off-heap.size=134217728b -D taskmanager.memory.network.max=134217730b -D taskmanager.memory.network.min=134217730b -D taskmanager.memory.framework.heap.size=134217728b -D taskmanager.memory.managed.size=536870920b -D taskmanager.cpu.cores=1.0 -D taskmanager.memory.task.heap.size=402653174b -D taskmanager.memory.task.off-heap.size=0b -D taskmanager.memory.jvm-metaspace.size=268435456b -D taskmanager.memory.jvm-overhead.max=201326592b -D taskmanager.memory.jvm-overhead.min=201326592b
config.sh 是会初始化了FLINK_TM_HEAP、FLINK_TM_MEM_MANAGED_SIZE、FLINK_TM_MEM_MANAGED_FRACTION、FLINK_TM_OFFHEAP、FLINK_TM_MEM_PRE_ALLOCATE、FLINK_TM_NET_BUF_FRACTION等变量, 这里由于不是脚本启动，所以启动TM需要指定这些变量值 


```
