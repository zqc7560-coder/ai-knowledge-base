package com.kb.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kb.domain.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
