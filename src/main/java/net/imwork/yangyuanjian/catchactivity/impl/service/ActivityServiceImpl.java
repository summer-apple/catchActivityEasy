package net.imwork.yangyuanjian.catchactivity.impl.service;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import com.baomidou.mybatisplus.mapper.Wrapper;
import com.baomidou.mybatisplus.toolkit.IdWorker;
import net.imwork.yangyuanjian.catchactivity.impl.assist.ConfigManager;
import net.imwork.yangyuanjian.catchactivity.impl.assist.PhoneCheck;
import net.imwork.yangyuanjian.catchactivity.impl.assist.RetMessage;
import net.imwork.yangyuanjian.catchactivity.impl.dao.ActivityRecordDao;
import net.imwork.yangyuanjian.catchactivity.impl.dao.ExchangeRecordDao;
import net.imwork.yangyuanjian.catchactivity.impl.dao.ShareRecordDao;
import net.imwork.yangyuanjian.catchactivity.impl.entity.*;
import net.imwork.yangyuanjian.catchactivity.spi.ActivityService;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

/**
 * Created by thunderobot on 2017/11/6.
 */
public class ActivityServiceImpl implements ActivityService{

    private Supplier<String> timeSuppile=()-> new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());

    @Resource
    private ActivityRecordDao activityRecordDao;
    @Resource
    private ExchangeRecordDao exchangeRecordDao;
    @Resource
    private ShareRecordDao shareRecordDao;
    @Override
    public String addActivityRecord(HttpServletRequest req, HttpServletResponse res) {
        //获取参数
        String mac=req.getParameter("mac");
        String phone=req.getParameter("phone");
        String flow=req.getParameter("flow");
        String locker="mac["+mac+"],phone["+phone+"],flow["+flow+"]";
        //获取手机号码对应的运营商,1移动,2联通,3电信,null虚拟或号码不正常
        Integer phoneType= PhoneCheck.getServiceProvider(phone);
        if(phoneType==null){
            return new RetMessage("1009","手机号格式不符或手机运营商为虚拟运营商！",null).toJson();
        }
        //判断数据库中是否已经存在3条记录
        List<ActivityRecord> records=null;
        EntityWrapper<ActivityRecord> wrapper=new EntityWrapper<>();
        wrapper.eq("phone",phone);
        //添加对应手机号的记录
        records=activityRecordDao.selectList(wrapper);
        wrapper=new EntityWrapper<>();
        //添加对应mac的记录
        wrapper.eq("mac",mac);
        records.addAll(activityRecordDao.selectList(wrapper));
        if(records.size()>=3)
            return new RetMessage("3009","活动次数已达到上限!无法再领取奖励!",null).toJson();
        Gift gift=null;
        //根据运营商判断结果查找对应的流量奖励
        switch (flow){
            case "50":{
                switch (phoneType){
                    case 1:gift=Gift.MOBILE_50;break;
                    case 2:gift=Gift.UNICOM_50;break;
                    case 3:gift=Gift.UNION_50;break;
                    default:return new RetMessage("2019","流量值参数异常!",null).toJson();
                }
            };break;
            case "100":{
                switch (phoneType){
                    case 1:gift=Gift.MOBILE_100;break;
                    case 2:gift=Gift.UNICOM_100;break;
                    case 3:gift=Gift.UNION_100;break;
                    default:return new RetMessage("2019","流量值参数异常!",null).toJson();
                }
            };break;
            default:return new RetMessage("2009","流量值参数异常!",null).toJson();
        }
        //添加活动记录,等到分享后才真正发放奖励
        ActivityRecord record=new ActivityRecord();
        record.setRecordId(IdWorker.getId());
        record.setMac(mac);
        record.setPhone(phone);
        record.setTime(timeSuppile.get());
        record.setFlows(gift.getGiftId());
        record.setRemark(gift.getName());
        record.insert();
        return new RetMessage("0000","参与活动成功",null).toJson();
    }

    @Override
    public String exchangeGift(ActivityRecord record) {
        //若活动记录标记为已经完成兑换了,则将不再兑换
        if(record.getExchanged().equals(ActivityRecord.HAS_EXCHANGED))
            return new RetMessage("5009","记录["+record+"]已经完成了奖励发放,无法再次兑换!", null).toJson();
        //根据活动记录查找对应的兑换记录
        EntityWrapper<ExchangeRecord> wrapper=new EntityWrapper<>();
        wrapper.eq("activity_record_id",record.getRecordId());
        List<ExchangeRecord> records=exchangeRecordDao.selectList(wrapper);
        //若兑换记录为空,则新建兑换
        if(!records.isEmpty()){
            ApiRequest request=new ApiRequest(record.getPhone(),record.getFlows());
            request.setKey(ConfigManager.get("key"));
            request.setAppsecret(ConfigManager.get("appsecret"));
            request.setNotify_url(ConfigManager.get("notifyUrl"));
            request.sign();
            String responseStr=request.getRequest();
            if(responseStr==null)
                return new RetMessage("4009","美阳响应空",null).toJson();
            ApiResponse response= JSON.parseObject(responseStr,ApiResponse.class);
            //装配兑换记录
            ExchangeRecord exchangeRecord=new ExchangeRecord();
            exchangeRecord.setGiftId(record.getFlows());
            exchangeRecord.setGiftName(record.getRemark());
            exchangeRecord.setMac(record.getMac());
            exchangeRecord.setPhone(record.getPhone());
            exchangeRecord.setRecordId(IdWorker.getId());
            exchangeRecord.setRecordTime(timeSuppile.get());
            exchangeRecord.setActivityRecordId(record.getRecordId());
            exchangeRecord.setRetryTimes(0);
            //若兑换成功,新增兑换记录,并更新对应的活动记录
            if(response.getCode().equals(ApiResponse.SUCCESS)){
                exchangeRecord.setOrderNum(response.getOrder_num());
                exchangeRecord.setStatus(ExchangeRecord.getStatusSuccess());
                exchangeRecord.insert();
                record.setExchanged(ActivityRecord.HAS_EXCHANGED);
                record.updateById();
            }else{
                return new RetMessage("6009","兑换奖励失败!",null).toJson();
            }
        }else{
            //若兑换记录不为空,而活动记录标记为未兑换,按已经兑换处理,更新活动记录为已经兑换
            record.setExchanged(ActivityRecord.HAS_EXCHANGED);
            record.updateById();
        }
        return new RetMessage("6009","兑换奖励成功!",null).toJson();
    }

    @Override
    public boolean checkPhone(String phone, String mac) {
        return false;
    }

    @Override
    public String nofity(HttpServletRequest req, HttpServletResponse res) {
        System.out.println(req.getParameterMap());
        return null;
    }

    @Override
    public String shareIn(HttpServletRequest req, HttpServletResponse res) {
        //获取参数
        String phone=req.getParameter("phone");
        String mac=req.getParameter("mac");
        //查找分享手机号与mac 对应的分享记录
        EntityWrapper<ShareRecord> wrapper=new EntityWrapper<>();
//        wrapper.eq("share_phone",phone);
        wrapper.eq("accept_mac",mac);
        List<ShareRecord> records=shareRecordDao.selectList(wrapper);
        //若接收分享的mac没有对应的分享记录
        if(records.isEmpty()){
            //根据分享的手机号查找对应的活动记录
            EntityWrapper<ActivityRecord> arWrapper=new EntityWrapper<>();
            arWrapper.eq("phone",phone);
            List<ActivityRecord> activityRecords=activityRecordDao.selectList(arWrapper);
            if(activityRecords.isEmpty()){
                //分享者并没有参与活动
                return new RetMessage("7009","分享手机号["+phone+"]没有对应活动记录!",null).toJson();
            }
            //添加分享记录
            ActivityRecord activityRecord=activityRecords.get(0);
            ShareRecord record=new ShareRecord();
            record.setAcceptMac(mac);
            record.setAcceptTime(timeSuppile.get());
            record.setRecordId(IdWorker.getId());
            record.setShareMac(activityRecord.getMac());
            record.insert();
            return new RetMessage("8009","mac["+mac+"]接受["+phone+"]邀请成功!",null).toJson();
        }else{
            //接受分享的mac已经接受了其他邀请
            return new RetMessage("8009","该mac["+mac+"]已经接受了其他邀请了!",null).toJson();
        }

    }

    @Override
    public String giftQuery(HttpServletRequest req, HttpServletResponse res) {
        return null;
    }
}