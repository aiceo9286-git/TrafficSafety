package com.sharn.trafficsafety;

import java.util.HashMap;
import java.util.Map;

/**
 * v2.6: 統一標籤處理工具類
 * 
 * 消除重複代碼，提供中英文標籤轉換
 */
public class LabelUtils {
    
    // 中文對照表
    private static final Map<String, String> CHINESE_LABELS = new HashMap<String, String>() {{
        put("person", "行人");
        put("people", "行人");
        put("bicycle", "腳踏車");
        put("bike", "腳踏車");
        put("car", "汽車");
        put("motorcycle", "機車");
        put("motorbike", "機車");
        put("bus", "公車");
        put("train", "火車");
        put("truck", "卡車");
        put("traffic light", "紅綠燈");
        put("trafficlight", "紅綠燈");
        put("stoplight", "號誌");
        put("signal", "號誌");
        put("stop sign", "停止標誌");
        put("airplane", "飛機");
    }};
    
    // 優先級（越低越優先）
    private static final Map<String, Integer> CLASS_PRIORITY = new HashMap<String, Integer>() {{
        put("person", 1);
        put("行人", 1);
        put("traffic light", 2);
        put("紅綠燈", 2);
        put("motorcycle", 3);
        put("機車", 3);
        put("car", 4);
        put("汽車", 4);
        put("bus", 5);
        put("公車", 5);
        put("truck", 6);
        put("卡車", 6);
        put("bicycle", 7);
        put("腳踏車", 7);
        put("stop sign", 8);
        put("停止標誌", 8);
        put("train", 9);
        put("火車", 9);
    }};
    
    // 危險權重（用於安全評估）
    private static final Map<String, Float> CLASS_WEIGHTS = new HashMap<String, Float>() {{
        put("person", 0.8f);
        put("行人", 0.8f);
        put("motorcycle", 0.9f);
        put("機車", 0.9f);
        put("car", 1.0f);
        put("汽車", 1.0f);
        put("truck", 1.1f);
        put("卡車", 1.1f);
        put("bus", 1.1f);
        put("公車", 1.1f);
        put("bicycle", 0.9f);
        put("腳踏車", 0.9f);
        put("stop sign", 0.7f);
        put("停止標誌", 0.7f);
    }};
    
    /**
     * 取得中文標籤
     */
    public static String getChineseLabel(String englishLabel) {
        if (englishLabel == null) return "";
        String lower = englishLabel.toLowerCase();
        return CHINESE_LABELS.getOrDefault(lower, englishLabel);
    }
    
    /**
     * 取得類別優先級
     */
    public static int getPriority(String label) {
        return CLASS_PRIORITY.getOrDefault(label, CLASS_PRIORITY.getOrDefault(
            getChineseLabel(label), 99));
    }
    
    /**
     * 取得危險權重
     */
    public static float getWeight(String label) {
        return CLASS_WEIGHTS.getOrDefault(label, CLASS_WEIGHTS.getOrDefault(
            getChineseLabel(label), 1.0f));
    }
    
    /**
     * 是否為行人相關
     */
    public static boolean isPedestrian(String label) {
        if (label == null) return false;
        String lower = label.toLowerCase();
        return lower.contains("person") || 
               lower.contains("pedestrian") ||
               lower.contains("people") ||
               lower.contains("行人");
    }
    
    /**
     * 是否為紅綠燈
     */
    public static boolean isTrafficLight(String label) {
        if (label == null) return false;
        String lower = label.toLowerCase();
        return lower.contains("traffic light") ||
               lower.contains("traffic_light") ||
               lower.contains("trafficlight") ||
               lower.contains("stoplight") ||
               lower.contains("signal") ||
               label.contains("紅綠燈") ||
               label.contains("號誌");
    }
    
    /**
     * 是否為車輛
     */
    public static boolean isVehicle(String label) {
        if (label == null) return false;
        String lower = label.toLowerCase();
        return lower.contains("car") || 
               lower.contains("motorcycle") ||
               lower.contains("bus") ||
               lower.contains("truck") ||
               lower.contains("bicycle") ||
               label.contains("汽車") ||
               label.contains("機車") ||
               label.contains("公車") ||
               label.contains("卡車") ||
               label.contains("腳踏車");
    }
}
