package com.kb.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kb.domain.entity.KbMember;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KbMemberMapper extends BaseMapper<KbMember> {
}
