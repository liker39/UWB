package com.example.uwb;

import androidx.fragment.app.Fragment;

public class GetParameters extends Fragment{
    public byte[] data;
    public float distance;
    public float azimuth;
    public float elevation;

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
        dataAnalysis(data);
    }

    public float getDistance() {
        return distance;
    }

    public void setDistance(float distance) {
        this.distance = distance;
    }

    public float getAzimuth() {
        return azimuth;
    }

    public void setAzimuth(float azimuth) {
        this.azimuth = azimuth;
    }

    public float getElevation() {
        return elevation;
    }

    public void setElevation(float elevation) {
        this.elevation = elevation;
    }

    // 1回のデータが何回かに分かれて送信されるので連結処理を行う
    private int readData = 0;
    String string = "";

    // データの連結処理と解析を行う
    private void dataAnalysis(byte[] data) {
        String str = new String(data);
        int check = str.indexOf("6200004D"); // 親機と通信をしているか確認
        int mark = string.indexOf("01111100"); // 距離、角度を取得するための目印

        if(check != -1){
            readData = 1; // 6200004D は 1ブロック目
        } else if(readData == 1){
            string += str;
            readData += 1; // 2ブロック目
        } else if(readData == 2 && mark != -1){
            string += str; // 3ブロック目
            // 距離取得
            String initDistance = string.substring(mark+11,mark+15);
            String distanceHexData = initDistance.substring(2,4) + initDistance.substring(0,2);
            float distance = (float)Integer.parseInt(distanceHexData,16);
            setDistance(distance);

            // 方位取得(Azimuth) xy平面
            String initAzimuth = string.substring(mark+15,mark+20);
            String hexDataAzimuth = initAzimuth.substring(3,5) + initAzimuth.substring(0,2);
            int a = Integer.parseInt(hexDataAzimuth,16);
            if(a > 32768) { a -= 65535; }
            float azimuth = (float) (a/Math.pow(2,7));
            setAzimuth(azimuth);

            // 高さ取得(Elevation) xz平面
            String initElevation = string.substring(mark+22,mark+26);
            String hexDataElevation = initElevation.substring(2,4) + initElevation.substring(0,2);
            a = Integer.parseInt(hexDataElevation,16);
            if(a > 32768) { a -= 65535; }
            float elevation = (float) (a/Math.pow(2,7));
            setElevation(elevation);

            // データの初期化
            readData = 0;
            string = "";
        }
    }

}
