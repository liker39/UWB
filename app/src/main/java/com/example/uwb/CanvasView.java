package com.example.uwb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class CanvasView extends View {
    Paint paint;

    public CanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint();
    }

    private int log;

//    private float[] logDataX;
//    private float[] logDataY;
    List<Float> logDataX = new ArrayList<Float>();
    List<Float> logDataY = new ArrayList<Float>();

    @Override
    protected void onDraw(Canvas canvas) {
        final float centerX = getWidth() * 0.5f;
        final float centerY = getHeight() * 0.5f;
        final float radius = Math.min(centerX, centerY);

        final float height = getHeight() * 0.8f;

        int num = TerminalFragment.getNum();

        float interval = height/num; // 1mの長さ

        // 背景
        canvas.drawColor(Color.argb(255, 200, 200, 255));

        // 円弧　推奨範囲の表示
        paint.setColor(Color.argb(150, 255, 255, 20));
//        canvas.drawArc(centerX-interval*2, (float) (centerY*1.9-interval*2),
//                centerX+interval*2, (float) (centerY*1.9+interval*2),
//                210,120,true,paint);
        paint.setStyle(Paint.Style.FILL);
        Path path = new Path();
        path.moveTo(centerX, (float) (centerY*1.9));
        path.lineTo((float) (centerX-centerY*1.8*Math.sqrt(3)), (float) (centerY*0.1));
        path.lineTo((float) (centerX+centerY*1.8*Math.sqrt(3)), (float) (centerY*0.1));
        path.close();
        canvas.drawPath(path,paint);


        // 線　距離の線　etc
        paint.setStrokeWidth(10);
        paint.setColor(Color.argb(255, 0, 120, 120));
        // (x1,y1,x2,y2,paint) 始点の座標(x1,y1), 終点の座標(x2,y2)
        canvas.drawLine(0, (float) (centerY*1.9), centerX*2, (float) (centerY*1.9), paint); // 横線
        canvas.drawLine(centerX, (float) (centerY*0.1), centerX, (float) (centerY*1.9), paint); // 縦線

        for(int i=1;i<num+1;i++){
            canvas.drawLine((float) (centerX*0.9), (float) (centerY*1.9-interval*i),
                            (float) (centerX*1.1), (float) (centerY*1.9-interval*i), paint); // 縦線
        }

        // 分度器用の線
        paint.setStrokeWidth(2);
        paint.setColor(Color.argb(100, 0, 120, 120));
        canvas.rotate(90,centerX, (float) (centerY*1.9));
        for(int i=0;i<18;i++){
            canvas.rotate(-10,centerX, (float) (centerY*1.9));
            canvas.drawLine(centerX, (float) (centerY*1.9), centerX, (float) (centerY*0.1), paint);
        }
        canvas.rotate(90,centerX, (float) (centerY*1.9));

        // テキスト
        paint.setStyle(Paint.Style.STROKE);
        paint.setTextSize(50);
        paint.setStrokeWidth(5);
        paint.setColor(Color.argb(255, 120, 120, 120));
        for(int i=1;i<num+1;i++){
            canvas.drawText(i+"m", (float) (centerX*1.2), (float) (centerY*1.9-interval*i), paint); // 縦線
        }
        canvas.drawText("-",(float) (centerX*1.9),(float) (centerY*1.85),paint);
        canvas.drawText("+",(float) (centerX*0.1),(float) (centerY*1.85),paint);


        // 矩形 パソコン
        paint.setColor(Color.argb(255, 50, 50, 50));
        paint.setStrokeWidth(0);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStyle(Paint.Style.FILL);
        // (x1,y1,x2,y2,paint) 左上の座標(x1,y1), 右下の座標(x2,y2)
        canvas.drawRect((float) (centerX*0.8), (float) (centerY*1.95),
                        (float) (centerX*1.2), (float) (centerY*2), paint);

        // parameter get
        float distance = TerminalFragment.getDistance();
        float azimuth = TerminalFragment.getAzimuth();
        float pointX = (float) (distance*Math.sin(Math.toRadians(azimuth)));
        float pointY = (float) (distance*Math.cos(Math.toRadians(azimuth)));

        float pointCircleX = (float) (centerX - interval*pointX/100);
        float pointCircleY = (float) (centerY*1.9 - interval*pointY/100);


        // ログを収集するかどうか
        if(TerminalFragment.getLog() == 1){
            log = 1;
            logDataX.add(pointCircleX);
            logDataY.add(pointCircleY);
        }

        // 全て表示するか、そのときだけ表示するか
        // 位置を表示
        paint.setColor(Color.argb(255, 200, 50, 50));
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        // (x1,y1,r,paint) 中心x1座標, 中心y1座標, r半径
        paint.setStyle(Paint.Style.FILL);


        if(log == 1 && TerminalFragment.getLog() == 0){
            log = 0;
            int colorInterval = logDataX.size()/255 + 1;
            for(int i=0;i<logDataX.size();i++){
                paint.setColor(Color.argb(255, 200, 10, 10));
                canvas.drawCircle(logDataX.get(i), logDataY.get(i), 10, paint);
            }
            logDataX = new ArrayList<Float>();
            logDataY = new ArrayList<Float>();

        } else {
            canvas.drawCircle(pointCircleX, pointCircleY, 15, paint);
        }

    }
}
