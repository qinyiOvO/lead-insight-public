package com.leadinsight.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户数据访问。只做数据存取，不含业务逻辑。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
