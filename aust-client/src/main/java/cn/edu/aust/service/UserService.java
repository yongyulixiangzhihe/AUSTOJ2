package cn.edu.aust.service;

import com.google.common.collect.Lists;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.modelmapper.ModelMapper;
import org.modelmapper.TypeToken;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import cn.edu.aust.common.constant.PosCode;
import cn.edu.aust.common.constant.RedisKey;
import cn.edu.aust.common.constant.user.UserStatus;
import cn.edu.aust.common.constant.user.UserType;
import cn.edu.aust.common.entity.ResultVO;
import cn.edu.aust.common.entity.Setting;
import cn.edu.aust.convert.UserConvert;
import cn.edu.aust.dto.UserDTO;
import cn.edu.aust.mapper.UserMapper;
import cn.edu.aust.pojo.entity.UserDO;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户service类
 *
 * @author Niu Li
 * @since  2017/1/22
 */
@Service
@Slf4j
public class UserService {
  @Resource
  private ModelMapper modelMapper;
  @Resource
  private UserMapper userMapper;
  @Resource
  private SettingService settingService;
  @Resource
  private MailService mailService;
  @Resource
  private StringRedisTemplate redisTemplate;

  /**
   * 得到当前客户端登录用户
   * todo 替换为spring security
   * @return 该用户
   */
  public UserDO getCurrent() {
    ServletRequestAttributes context = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    UserDTO userDTO = (UserDTO) context.getAttribute("userinfo",
        RequestAttributes.SCOPE_SESSION);
    Long id = userDTO != null ? userDTO.getId() : null;
    return id == null ? null : userMapper.selectByPrimaryKey(id);
  }

  /**
   * 根据email查询用户
   *
   * @param email 邮箱
   * @return 用户
   */
  public UserDTO findByEmail(String email) {
    UserDO userDO = userMapper.findByEmail(email);
    return UserConvert.user2UserDTO(userDO);
  }

  /**
   * 根据用户id查找用户信息
   * @param id 用户id
   * @return 用户信息
   */
  public UserDTO findById(Long id){
    UserDO userDO = userMapper.selectByPrimaryKey(id);
    return UserConvert.user2UserDTO(userDO);
  }

  /**
   * 用户注册
   * @return 注册后用户
   */
  @Transactional
  public UserDTO registerUser(String passwd, String ip, String nickname, String email){
    UserDO userDO = new UserDO();
    userDO.setPassword(DigestUtils.sha256Hex(passwd));
    userDO.setCreatedate(new Date());
    userDO.setModifydate(userDO.getCreatedate());
    userDO.setIp(ip);
    userDO.setNickname(nickname);
    userDO.setEmail(email);
    userDO.setPoint(0);
    userDO.setType(UserType.GENERAL.value);
    userDO.setStatus(UserStatus.WAIT4EMAIL_CHECK.value);//设置待验证状态
    userMapper.insertSelective(userDO);
    //发送邮件,验证
    mailService.sendRegister(email);
    return UserConvert.user2UserDTO(userDO);
  }

  /**
   * 检验Email验证
   * @param token 验证戳
   * @param resultVO 返回封装类
   * @return true成功
   */
  @Transactional
  public Boolean checkEmailToken(String token, ResultVO resultVO){
    String email = redisTemplate.opsForValue().get(token);
    if (StringUtils.isEmpty(email)) {
      resultVO.buildWithPosCode(PosCode.URL_ERROR);
      return false;
    }
    //判断用户状态
    UserDO userDO = userMapper.findByEmail(email);
    if (userDO.getId() == null || userDO.getStatus() != UserStatus.WAIT4EMAIL_CHECK.value) {
      resultVO.buildWithPosCode(PosCode.ALREADY_REGISTER);
      return false;
    }
    //更新用户 已验证
    userDO.setStatus(UserStatus.NORMAL.value);
    userDO.setModifydate(new Date());
    userMapper.updateByPrimaryKeySelective(userDO);
    redisTemplate.delete(token);
    return true;
  }
  /**
   * 检查用户是否可登陆
   *
   * @param userDTO  用户
   * @param passwd   用户密码(明文)
   * @param ip       登陆ip
   * @param resultVO 错误写回实体
   * @return true 可登陆
   */
  public Boolean checkCanLogin(UserDTO userDTO, String passwd, String ip, ResultVO resultVO) {
    UserDO userDO = userMapper.selectByPrimaryKey(userDTO.getId());
    Setting setting = settingService.getSetting();
    //验证是否冻结
    if (userDO.getStatus() == UserStatus.FREEZE.value) {
      resultVO.buildWithPosCode(PosCode.USER_FREEZE);
      return false;
    }
    //验证锁定状态
    if (userDO.getIsLock() == 1) {
      int accountLockTime = setting.getAccountLockTime();
      //锁定时间0,则永久锁定
      if (accountLockTime == 0) {
        resultVO.buildWithPosCode(PosCode.USER_LOCKED);
        return false;
      }
      Date lockdate = userDO.getLockdate();
      Date unlockdate = DateUtils.addMinutes(lockdate, accountLockTime);
      if (new Date().after(unlockdate)) {
        userDO.setIsLock(0);
        userDO.setLoginfail(0);
        userDO.setLockdate(null);
        userMapper.updateByPrimaryKeySelective(userDO);
      } else {
        resultVO.buildWithPosCode(PosCode.USER_LOCKED);
        return false;
      }
    }
    //验证密码
    if (!userDO.getPassword().equals(DigestUtils.sha256Hex(passwd.trim()))) {
      int accountLockCount = userDO.getLoginfail() + 1;
      if (accountLockCount > setting.getAccountLockCount()) {
        userDO.setLockdate(new Date());
        userDO.setIsLock(1);
        log.info("用户:{}已被锁定", userDO.getEmail());
      }
      userDO.setLoginfail(accountLockCount);
      userMapper.updateByPrimaryKeySelective(userDO);
      if (userDO.getIsLock() == 1) {
        resultVO.buildWithPosCode(PosCode.USER_LOCKED);
        return false;
      } else {
        String msg = "密码错误,若再错误" + (setting.getAccountLockCount() - accountLockCount + 1) + "次,则锁定账户";
        resultVO.buildWithMsgAndStatus(PosCode.USER_LOCKED, msg);
        return false;
      }
    }
    //登录成功
    userDO.setIp(ip);
    userDO.setModifydate(new Date());
    userDO.setLoginfail(0);
    userMapper.updateByPrimaryKeySelective(userDO);
    return true;
  }

  /**
   * 查询用户排名,先去redis中获取排名id,然后根据id查
   *
   * @return 排名后的用户
   */
  public List<UserDTO> queryForRank() {
    Set<String> userIds = redisTemplate.opsForZSet().reverseRange(RedisKey.RANK_USER,0,10000);
    List<UserDO> userDOS = Lists.newArrayList();
    if (!CollectionUtils.isEmpty(userIds)) {
      userDOS.addAll(userMapper.queryBaseByIds(userIds.stream().map(NumberUtils::toLong)
          .collect(Collectors.toSet())));
    }else {
      userDOS.addAll(userMapper.queryForRank());
    }
    return modelMapper.map(userDOS, new TypeToken<List<UserDTO>>() {}.getType());
  }

  /**
   * 查询展示到首页的用户
   *
   * @return 展示到首页用户
   */
  public List<UserDTO> queryToIndexShow() {
    List<UserDO> userDOS = userMapper.queryToShow();
    if (CollectionUtils.isEmpty(userDOS)) {
      return Collections.emptyList();
    }
    return modelMapper.map(userDOS, new TypeToken<List<UserDTO>>() {
    }.getType());
  }

  /**
   * 判断用户名或者邮箱是否存在
   *
   * @param email    邮箱
   * @return true存在
   */
  public boolean judgeEmail(String email) {
    UserDO userDO = new UserDO();
    if (StringUtils.isNoneEmpty(email)) {
      userDO.setEmail(email);
      userDO = userMapper.selectOne(userDO);
    }
    return userDO != null;
  }

  /**
   * 判断用户名是否被禁用
   *
   * @param username 用户名
   * @return true被禁用
   */
  public boolean usernameIsDisabled(String username) {
    if (StringUtils.isEmpty(username)) return false;
    Setting setting = settingService.getSetting();
    String[] disabledName = setting.getDisabledUsernames().split(",");
    for (String s : disabledName) {
      if (StringUtils.equalsIgnoreCase(s, username)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 更新用户不为null的字段
   * @param userDO
   * @return
   */
  @Transactional
  public boolean updateUserSelective(UserDO userDO){
    int k = userMapper.updateByPrimaryKeySelective(userDO);
    return k>0;
  }

}
