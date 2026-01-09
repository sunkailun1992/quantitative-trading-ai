package com.trading.controller;

import lombok.Data;

/**
 * 🔑 钱包查询请求体
 */
@Data
public class WalletRequest {

    /** Bybit API Key */
    private String apiKey;

    /** Bybit API Secret */
    private String apiSecret;
}
