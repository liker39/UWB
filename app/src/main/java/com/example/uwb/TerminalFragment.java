package com.example.uwb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    private TextView receiveText;
    private TextView testText;
    private TextView sendText;
    private ControlLines controlLines;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean controlLinesEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    private CanvasView canvasView;

    private Button minusButton; // 距離[m]を1減らす
    private Button plusButton; // 距離[m]を1増やす
    private Button startButton; // ログ取得開始
    private Button stopButton; // ログ取得終了
    private Button resetButton; // ログ取得結果リセット

    private File file; // 保存先のファイルを用意

    private Calendar cal; // ファイル名
    private String fileName=""; // ファイル名

    private EditText fileNameEditText; // 入力部分
    private ImageButton fileNameEditButton; // 入力ボタン
    private TextView fileNameText; // 入力内容確認部分
    private Context context;

    public TerminalFragment(Context context) {
        this.context = context;
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connect(granted);
                }
            }
        };
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");


    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB));
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if(controlLinesEnabled && controlLines != null && connected == Connected.True)
            controlLines.start();
    }

    @Override
    public void onPause() {
        getActivity().unregisterReceiver(broadcastReceiver);
        if(controlLines != null)
            controlLines.stop();
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    // 最大距離数
    public static int getNum() {
        return num;
    }

    private static int num = 4;

    public static int getLog() {
        return log;
    }

    // ログを取得するかどうか
    private static int log = 0;
    private int showResult = 0;

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        testText = view.findViewById(R.id.get_text);
        testText.setTextColor(getResources().getColor(R.color.colorGetText));

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        controlLines = new ControlLines(view);

        // キャンバス追加
        canvasView = view.findViewById(R.id.canvas);

        // ボタン追加
        minusButton = view.findViewById(R.id.minus_btn);
        plusButton = view.findViewById(R.id.plus_btn);
        startButton = view.findViewById(R.id.start_btn);
        stopButton = view.findViewById(R.id.stop_btn);
        resetButton = view.findViewById(R.id.reset_btn);

        // プラス
        plusButton.setOnClickListener( v -> { num += 1; });

        // マイナス
        minusButton.setOnClickListener( v -> { if(num > 1) num -= 1; });

        // ログ取得開始
        startButton.setOnClickListener( v -> {
            log = 1;
            showResult = 0;
            saveData = "";

            cal = Calendar.getInstance();
            String year = String.valueOf(cal.get(Calendar.YEAR));
            String month = String.valueOf(cal.get(Calendar.MONTH) + 1);
            if(month.length()==1){ month = "0" + month; }
            String date = String.valueOf(cal.get(Calendar.DATE));
            if(date.length()==1){ date = "0" + date; }
            String hour = String.valueOf(cal.get(Calendar.HOUR_OF_DAY));
            if(hour.length()==1){ hour = "0" + hour; }
            String minute = String.valueOf(cal.get(Calendar.MINUTE));
            if(minute.length()==1){ minute = "0" + minute; }
            String second = String.valueOf(cal.get(Calendar.SECOND));
            if(second.length()==1){ second = "0" + second; }

            fileName = year + month + date + "_" + hour + minute + second;
            fileNameText.setText(fileName + ".txt");
        });

        // ログ取得終了
        stopButton.setOnClickListener( v -> {
            if(log == 1){
                file = new File(context.getFilesDir(), fileName+".txt");
                fileNameText.setText("Saved " + fileName+".txt");

                saveFile(saveData);

                canvasView.invalidate();
                showResult = 1;

                // ログデータの平均と標準偏差表示
                logAnalyze();

                // データ初期化
                log = 0;
                fileName = "";
                saveData = "";
                logDataDistance = new ArrayList<Float>();
                logDataAzimuth = new ArrayList<Float>();
                logDataElevation = new ArrayList<Float>();

            } else {
                Toast.makeText(context,"Please press start button",Toast.LENGTH_LONG);
                fileNameText.setText("Not started");
            }
        });

        // ログ取得結果表示リセット
        resetButton.setOnClickListener( v -> {
            showResult = 0;
            log = 0;
            fileName = "";
            saveData = "";
            logDataDistance = new ArrayList<Float>();
            logDataAzimuth = new ArrayList<Float>();
            logDataElevation = new ArrayList<Float>();

            fileNameText.setText("Reset");
        });

        // ファイル名取得
        fileNameText = view.findViewById(R.id.file_name_status_text);

        return view;
    }


    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        menu.findItem(R.id.controlLines).setChecked(controlLinesEnabled);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.controlLines) {
            controlLinesEnabled = !controlLinesEnabled;
            item.setChecked(controlLinesEnabled);
            if (controlLinesEnabled) {
                controlLines.start();
            } else {
                controlLines.stop();
            }
            return true;
        } else if (id == R.id.sendBreak) {
            try {
                usbSerialPort.setBreak(true);
                Thread.sleep(100);
                status("send BREAK");
                usbSerialPort.setBreak(false);
            } catch (Exception e) {
                status("send BREAK failed: " + e.getMessage());
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        connect(null);
    }

    private void connect(Boolean permissionGranted) {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;
        if(device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if(driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(Constants.INTENT_ACTION_GRANT_USB), flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            service.connect(socket);
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect();
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        controlLines.stop();
        service.disconnect();
        usbSerialPort = null;
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                data = (str + newline).getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (SerialTimeoutException e) {
            status("write timeout: " + e.getMessage());
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void receive(byte[] data) {
        if(hexEnabled) {
            receiveText.append(TextUtil.toHexString(data) + '\n');
        } else {
            String msg = new String(data);
            if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                // don't show CR as ^M if directly before LF
                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                // special handling if CR and LF come in separate fragments
                if (pendingNewline && msg.charAt(0) == '\n') {
                    Editable edt = receiveText.getEditableText();
                    if (edt != null && edt.length() > 1)
                        edt.replace(edt.length() - 2, edt.length(), "");
                }
                pendingNewline = msg.charAt(msg.length() - 1) == '\r';
            }
            receiveText.append(TextUtil.toCaretString(msg, newline.length() != 0));
//                receiveText.append();
//                tmp += 1;
        }
    }

    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    // 1回のデータが何回かに分かれて送信されるので連結処理を行う
    private int readData = 0; // どのタイミングのデータなのかを判断
    String string = ""; // 送られてくるデータの連結処理

    private String saveData = ""; // ログに保存する用の文字列
    private List<Float> logDataDistance = new ArrayList<Float>();
    private List<Float> logDataAzimuth = new ArrayList<Float>();
    private List<Float> logDataElevation = new ArrayList<Float>();

    private static float distance; // 距離
    private static float azimuth; // XY方面の角度
    private static float elevation; // XZ方面の角度

    public static float getDistance() {
        return distance;
    }

    public static float getAzimuth() {
        return azimuth;
    }

    public static float getElevation() {
        return elevation;
    }

    private void parameters(byte[] data){
        String str = new String(data);

        int check = str.indexOf("6200004D"); // 親機と通信をしているか確認
        if(check != -1){  // 6200004D があるデータは 1ブロック目
            readData = 1;
            string = str;
        } else if(readData == 1){  // 2ブロック目
            readData = 2;
            string += str;
        } else if(readData == 2){  // 3ブロック目
            readData = 0;
            string += str;

            // タイムスタンプ取得
            int timeStampCheck = string.indexOf("6200004D");
            String initTimeStamp = string.substring(timeStampCheck+9,timeStampCheck+13);;
            String hexDataTimeStamp = initTimeStamp.substring(2,4) + initTimeStamp.substring(0,2);
            Float timeStamp = (float)Integer.parseInt(hexDataTimeStamp,16);

            // 距離取得
//                String initDistance = string.substring(mark+11,mark+15);
            String initDistance = string.substring(timeStampCheck+74,timeStampCheck+78);
            String hexDataDistance = initDistance.substring(2,4) + initDistance.substring(0,2);
            distance = (float)Integer.parseInt(hexDataDistance,16);

            // 方位取得(Azimuth) xy平面
//                String initAzimuth = string.substring(mark+15,mark+20);
            String initAzimuth = string.substring(timeStampCheck+78,timeStampCheck+83);
            String hexDataAzimuth = initAzimuth.substring(3,5) + initAzimuth.substring(0,2);
            int a = Integer.parseInt(hexDataAzimuth,16);
            if(a > 32768) { a -= 65536; }
            azimuth = (float) (a/Math.pow(2,7));

            // 高さ取得(Elevation) xz平面
//                String initElevation = string.substring(mark+22,mark+26);
            String initElevation = string.substring(timeStampCheck+85,timeStampCheck+89);
            String hexDataElevation = initElevation.substring(2,4) + initElevation.substring(0,2);
            int b = Integer.parseInt(hexDataElevation,16);
            if(b > 32768) { b -= 65536; }
            elevation = (float) (b/Math.pow(2,7));

            // logデータ取得
//                if(log == 1){ saveData += hexDataTimeStamp + "," +hexDataDistance + "," + hexDataAzimuth + "," + hexDataElevation + "\n"; }
            if(log == 1){
                // 元データ　16進数
//                saveData += hexDataTimeStamp + "," +hexDataDistance + "," + hexDataAzimuth + "," + hexDataElevation + "\n";
                // 計算した結果 10進数
                saveData += Math.round(timeStamp) + "," +Math.round(distance) + ","
                        + Math.round(azimuth) + "," + Math.round(elevation) + "\n";
                logDataDistance.add(distance);
                logDataAzimuth.add(azimuth);
                logDataElevation.add(elevation);
            }

            // 結果表示中でないとき、データとキャンバス更新
            if(showResult == 0) {
                // 表示
                testText.setTextSize(24);
//                    testText.setText("Distance: " + hexDataDistance + "cm\nAzimuth: " + hexDataAzimuth + "°\nElevation: " + hexDataElevation + "°");
                testText.setText("Distance: " + distance + "cm\nAzimuth: " + azimuth + "°\nElevation: " + elevation + "°");
                canvasView.invalidate();
            }

        }
    }

    public float calAverage(List<Float> listData){
        float sum = 0;
        for(int i=0; i<listData.size();i++){
            sum += listData.get(i);
        }
        return sum/listData.size();
    }

    public float calStandardDeviation(float ave, List<Float> listData){
        float sum = 0;
        for(int i=0; i<listData.size();i++){
            sum += Math.pow(listData.get(i) - ave, 2);
        }
        return (float) Math.sqrt(sum/listData.size());
    }

    public void logAnalyze(){
        // distanceの平均、標準偏差表示
        float aveDistance = calAverage(logDataDistance);
        float standardDeviationDistance = calStandardDeviation(aveDistance,logDataDistance);

        // azimuthの平均、標準偏差表示
        float aveAzimuth = calAverage(logDataAzimuth);
        float standardDeviationAzimuth = calStandardDeviation(aveAzimuth,logDataAzimuth);

        // elevationの平均、標準偏差表示
        float aveElevation = calAverage(logDataElevation);
        float standardDeviationElevation = calStandardDeviation(aveElevation,logDataElevation);

        testText.setTextSize(20);
        testText.setText("Distance Ave: " + aveDistance + "cm, SD: " + standardDeviationDistance + "cm\n" +
                "Azimuth Ave: " + aveAzimuth + "°, SD: " + standardDeviationAzimuth + "°\n" +
                "Elevation Ave: " + aveElevation + "°, SD: " + standardDeviationElevation + "°");
    }


    // ファイルを保存
    public void saveFile(String str) {
        // try-with-resources
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(str);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
        if(controlLinesEnabled)
            controlLines.start();
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        receive(data);
        parameters(data);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Handler mainLooper;
        private final Runnable runnable;
        private final LinearLayout frame;
        private final ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        ControlLines(View view) {
            mainLooper = new Handler(Looper.getMainLooper());
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks

            frame = view.findViewById(R.id.controlLines);
            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (connected != Connected.True) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) { ctrl = "RTS"; usbSerialPort.setRTS(btn.isChecked()); }
                if (btn.equals(dtrBtn)) { ctrl = "DTR"; usbSerialPort.setDTR(btn.isChecked()); }
            } catch (IOException e) {
                status("set" + ctrl + " failed: " + e.getMessage());
            }
        }

        private void run() {
            if (connected != Connected.True)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getControlLines();
                rtsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RTS));
                ctsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CTS));
                dtrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DTR));
                dsrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DSR));
                cdBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CD));
                riBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RI));
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (IOException e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        void start() {
            frame.setVisibility(View.VISIBLE);
            if (connected != Connected.True)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getSupportedControlLines();
                if (!controlLines.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CD))   cdBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.RI))   riBtn.setVisibility(View.INVISIBLE);
                run();
            } catch (IOException e) {
                Toast.makeText(getActivity(), "getSupportedControlLines() failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        void stop() {
            frame.setVisibility(View.GONE);
            mainLooper.removeCallbacks(runnable);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }
    }

}
