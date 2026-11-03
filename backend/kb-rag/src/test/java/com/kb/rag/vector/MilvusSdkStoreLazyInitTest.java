package com.kb.rag.vector;

import com.kb.rag.ai.AiClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * MilvusSdkStore 惰性连接回归测试（M12.1 修复的钉死）。
 *
 * 事故：SDK 客户端在构造函数里同步握 gRPC，而 Milvus 是可选组件
 * （start-all.bat 的 run-infra 只起 mysql/redis/pgvector）——Docker 重启后
 * kb-milvus 处于 Exited 时，pgvector 默认用户无辜躺枪：Spring 上下文崩、后端秒退。
 * 本测试断言"用不可达地址构造也不许有任何网络行为/异常"，防止 eager 连接回归。
 */
class MilvusSdkStoreLazyInitTest {

    @Test
    @DisplayName("构造函数零网络：不可达地址(port 1)也必须静默成功")
    void constructorMustNotConnect() {
        AiClientFactory ai = mock(AiClientFactory.class);
        assertThatCode(() -> new MilvusSdkStore(ai, "http://127.0.0.1:1", 1024))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("name() 零网络：身份查询不触发连接（Router/日志频繁调用）")
    void nameMustNotConnect() {
        AiClientFactory ai = mock(AiClientFactory.class);
        MilvusSdkStore s = new MilvusSdkStore(ai, "http://127.0.0.1:1", 1024);
        assertThatCode(s::name).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(s.name()).isEqualTo("milvus");
    }

    @Test
    @DisplayName("partitionName 纯函数：kb_ 前缀约定（删库 drop 分区/检索分区定位共用）")
    void partitionNaming() {
        org.assertj.core.api.Assertions.assertThat(MilvusSdkStore.partitionName(42L)).isEqualTo("kb_42");
    }
}
