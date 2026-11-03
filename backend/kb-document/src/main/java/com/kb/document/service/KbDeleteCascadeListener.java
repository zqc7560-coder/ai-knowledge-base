package com.kb.document.service;

import com.kb.domain.kb.KbDeletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 删库级联清理监听器（M12.1）。
 * KbService.delete（kb-common）发布 {@link KbDeletedEvent}，本监听器（kb-document）执行 purgeKb——
 * 依赖方向保持 kb-document→kb-common，无反向耦合。
 *
 * <h3>同步、吞错</h3>
 * - 同步执行（默认 @EventListener 语义）：删库 HTTP 返回前清完，不留"库没了但向量还在"的时间窗。
 * - purgeKb 内部异常吞掉只 warn：MySQL 软删已成功=用户视角库已消失；向量/文件清理失败属可补偿的
 *   孤儿（重建索引/定期清扫可兜底），绝不让它把 delete 接口炸回 500 让用户误以为删库失败。
 */
@Slf4j
@Component
public class KbDeleteCascadeListener {

    private final DocumentService docService;

    public KbDeleteCascadeListener(DocumentService docService) {
        this.docService = docService;
    }

    @EventListener
    public void onKbDeleted(KbDeletedEvent e) {
        try {
            docService.purgeKb(e.kbId());
            log.info("kb cascade purge done kb#{} by {}", e.kbId(), e.operator());
        } catch (Exception ex) {
            log.warn("kb cascade purge FAILED kb#{} (orphan vectors/files possible, reindex/reconcile can reclaim)", e.kbId(), ex);
        }
    }
}
