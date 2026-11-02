package com.kb.document.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kb.domain.entity.ProcessTask;
import com.kb.domain.mapper.ProcessTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动恢复（M7.1 欠账清偿——docs/05 如实记录"任务态在内存重启即清"的补集）。
 *
 * <h3>两件事，顺序有讲究</h3>
 * 1) 卡住的文档：上次进程死亡时正在 PARSING/CHUNKED/EMBEDDING 的，清残留后重新排队，
 *    自动续到 READY/FAILED（复用 DocumentService.recoverStuck，管道本身幂等）。
 * 2) 悬空的 reindex/migrate 任务行：DB 里 RUNNING 但执行线程随旧进程已死——
 *    标 FAILED:服务重启中断，管理页可见、用户可一键重触发。必须在文档恢复之后：
 *    reindex 重跑期间可能再次把文档置中间态，若先扫任务会把"本次重启后立刻开跑的新任务"误标。
 *    （实际此刻 tasksRunning 恒空，不会撞车；顺序是防御性设计。）
 */
@Slf4j
@Component
public class StartupRecoveryRunner {

    private final DocumentService docPipeline;
    private final ProcessTaskMapper taskLog;

    public StartupRecoveryRunner(DocumentService docPipeline, ProcessTaskMapper taskLog) {
        this.docPipeline = docPipeline;
        this.taskLog = taskLog;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void recover() {
        int n = docPipeline.recoverStuck();
        if (n > 0) log.info("M7.1 recovery: {} stuck documents re-queued for pipeline", n);
        int m = taskLog.update(null, new LambdaUpdateWrapper<ProcessTask>()
                .eq(ProcessTask::getState, "RUNNING")
                .set(ProcessTask::getState, "FAILED: 服务重启中断（可重新发起）"));
        if (m > 0) log.info("M7.1 recovery: {} orphaned tasks marked interrupted", m);
    }
}
