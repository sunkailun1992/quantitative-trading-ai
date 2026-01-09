package com.trading.aliyun;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @ClassName DingDing
 * @Description 钉钉
 * @Author 孙凯伦
 * 
 * @Email 376253703@qq.com
 * @Time 2021/6/25 10:35 上午
 */
@Component
public class DingDing {
    /**
     * 钉钉密钥
     */
    public static String secret;
    /**
     * 钉钉机器人地址
     */
    public static String url;

    @Value("${aliyun.dingding.secret}")
    public void setSecret(String secret) {
        DingDing.secret = secret;
    }


    @Value("${aliyun.dingding.url}")
    public void setUrl(String url) {
        DingDing.url = url;
    }
}
