package com.kb.security.config;

import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.domain.entity.User;
import com.kb.domain.mapper.UserMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 角色/权限数据源桥接。
 *
 * <h3>为什么必须有这个类</h3>
 * Sa-Token 的 @SaCheckRole/@SaCheckPermission 注解本身不查库——它把
 * "loginId 有哪些角色/权限"的判定外包给 StpInterface 实现。缺了这个 Bean，
 * 所有角色校验都会失败（表现为 ADMIN 也被 2002 拒绝，极难排查）。
 *
 * <h3>角色模型</h3>
 * 全局角色只有 USER/ADMIN 两值（users.role）；知识库级的 READ/ASK/EDIT 不在这套
 * 体系里，那是 KbAccessService 的业务判定（库级授权无法用全局角色表达）。
 * 每次校验实时查库而非读缓存：角色变更需即时生效，admin 量级查询成本可忽略。
 */
@Component
public class StpRoleInterface implements StpInterface {

    private final UserMapper users;

    public StpRoleInterface(UserMapper users) { this.users = users; }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of(); // 注解式权限未用，留空
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        User u = users.selectById(Long.valueOf(loginId.toString()));
        return u == null ? List.of() : List.of(u.getRole());
    }
}
