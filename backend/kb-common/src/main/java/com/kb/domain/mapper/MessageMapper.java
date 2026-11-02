package com.kb.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kb.domain.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
