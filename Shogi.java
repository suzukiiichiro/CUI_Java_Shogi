import java.io.*;
import java.net.*;
import java.util.*;
import java.util.Random;
import java.util.Vector;

class CSAProtocol implements Runnable,Constants {
  Socket s;
  InputStream is;
  OutputStream os;
  BufferedReader ir;
  BufferedWriter ow;
  Thread rcvThread=null;
  volatile boolean wait=true;
  volatile boolean waitTime=false;
  int time[]=new int[2];
  String recieved="";
  int turn=-1;
  Kyokumen k=new Kyokumen();
  String nameSente;
  String nameGote;
  // server:接続先サーバ名
  // port: 接続先サーバのポート番号
  public CSAProtocol(String server,int port) throws IOException {
    // サーバの名前を解決
    InetAddress address=
      InetAddress.getByName(server);
    // ソケットの生成
    s=new Socket(address,port);
    // 出力ストリームを取得
    os = s.getOutputStream();
    ow = new BufferedWriter(
           new OutputStreamWriter(os));
    // 入力ストリームを取得
    is = s.getInputStream();
    ir = new BufferedReader(
           new InputStreamReader(is));
  }
  // id:ログインID
  // passwd:ログインパスワード
  // 戻り値:1:ログイン成功 0:ログイン失敗
  public int login(String id,String passwd) throws IOException {
    ow.write("LOGIN "+id+" "+passwd+"\n");
    ow.flush();
    String recv=ir.readLine();
    if (recv.equals("LOGIN:"+id+" OK")) {
      return 1;
    }
    return 0;
  }
  // CHALLENGEコマンドを送付
  public void challenge() throws IOException {
    ow.write("CHALLNEGE\n");
    ow.flush();
  }
  public void logout() throws IOException {
    ow.write("LOGOUT\n");
    ow.flush();
  }
  public void resign() throws IOException {
    ow.write("%TORYO\n");
    ow.flush();
  }
  // ゲーム開始を待つ。
  // 本来なら、ゲームの条件を解析するべきだが、
  // ここでは、手番だけを解析する。
  public int waitGameStart(Kyokumen k) throws IOException {
    int teban=-1;
    k.initHirate();
    this.k.initHirate();
    String recv=ir.readLine();
    // ゲーム開始情報の解析：手番だけ解析。
    while(!recv.equals("END Game_Summary")) {
      System.out.println(recv);
      if (recv.equals("Your_Turn:+")) {
        teban=SENTE;
      }
      if (recv.equals("Your_Turn:-")) {
        teban=GOTE;
      }
      if (recv.startsWith("Name+:")) {
        nameSente=recv.substring(6);
      }
      if (recv.startsWith("Name-:")) {
        nameGote=recv.substring(6);
      }
      recv=ir.readLine();
      // 平手から、手を進めている可能性がある…。
      if (recv.equals("+")) {
        continue;
      }
      if (recv.startsWith("+")) {
        int from=Integer.parseInt(
            recieved.substring(1,3),16);
        int to=Integer.parseInt(
            recieved.substring(3,5),16);
        int koma;
        for(koma=0;koma<16;koma++) {
          if (recieved.substring(5,7).
              equals(KomaStr[koma])) {
            break;
          }
        }
        koma|=SENTE;
        boolean promote=(from!=0 && k.ban[from]!=koma);
        int capture=k.ban[to];
        Te ret=new Te(koma,from,to,promote,capture);
        k.move(ret);
        this.k.move(ret);
        int mytime=0;
        try {
          mytime=Integer.parseInt(recv.substring(recv.indexOf(",T")+2));
        }catch(NumberFormatException ne){
        }
        k.spentTime[0]+=mytime;
        k.teban=GOTE;
        k.tesu++;
      }
      if (recv.startsWith("-")) {
        int from=Integer.parseInt(
            recieved.substring(1,3),16);
        int to=Integer.parseInt(
            recieved.substring(3,5),16);
        int koma;
        for(koma=0;koma<16;koma++) {
          if (recieved.substring(5,7).
              equals(KomaStr[koma])) {
            break;
          }
        }
        koma|=GOTE;
        boolean promote=(from!=0 && k.ban[from]!=koma);
        int capture=k.ban[to];
        Te ret=new Te(koma,from,to,promote,capture);
        k.move(ret);
        this.k.move(ret);
        int mytime=0;
        try {
          mytime=Integer.parseInt(recv.substring(recv.indexOf(",T")+2));
        }catch(NumberFormatException ne){
        }
        k.spentTime[1]+=mytime;
        k.teban=SENTE;
        k.tesu++;
      }
    }
    System.out.println(recv);
    // ゲームの開始に同意する。
    ow.write("AGREE\n");
    ow.flush();
    // 「START」が送られるのを待つ。
    recv=ir.readLine();
    if (!recv.startsWith("START:")) {
      teban=-1;
    }
    turn=teban;
    return teban;
  }
  private static final String KomaStr[]={
    "",
    "FU","KY","KE","GI","KI","KA","HI","OU",
    "TO","NY","NK","NG","","UM","RY"
  };
  // 自分の指し手を送る
  public int sendTe(Te t) throws IOException,NumberFormatException {
    if (rcvThread==null) {
      // スレッドの開始
      wait=true;
      rcvThread = new Thread(this);
      rcvThread.start();
    }
    wait=true;
    waitTime=true;
    k.move(t);
    if (turn==SENTE) {
      ow.write("+");
    } else {
      ow.write("-");
    }
    if (t.from==0) {
      ow.write("00");
    } else {
      ow.write(Integer.toHexString(t.from));
    }
    ow.write(Integer.toHexString(t.to));
    if (t.promote) {
      // 成り駒
      ow.write(KomaStr[(t.koma+8)%16]);
    } else {
      ow.write(KomaStr[t.koma%16]);
    }
    ow.write("\n");
    ow.flush();
    while(waitTime) {
      // 所要時間が帰ってくるのを待つ
      try {
        Thread.sleep(100);
      }catch(Exception e){
      }
    }
    if (turn==SENTE) {
      return time[0];
    } else {
      return time[1];
    }
  }
  // 相手の手を待つ
  public Te recvTe() throws IOException,NumberFormatException {
    if (rcvThread==null) {
      // スレッドの開始
      wait=true;
      rcvThread = new Thread(this);
      rcvThread.start();
    }
    while(wait) {
      // 待つ…。
      try {
        Thread.sleep(100);
      }catch(Exception e){
      }
    }
    // recievedに相手の手が入ってるので解析
    int from=Integer.parseInt(
      recieved.substring(1,3),16);
    int to=Integer.parseInt(
      recieved.substring(3,5),16);
    int koma;
    for(koma=0;koma<16;koma++) {
      if (recieved.substring(5,7).
           equals(KomaStr[koma])) {
        break;
      }
    }
    if (turn==SENTE) {
      koma|=GOTE;
    } else {
      koma|=SENTE;
    }
    boolean promote=(from!=0 && k.ban[from]!=koma);
    int capture=k.ban[to];
    if (promote) koma=koma & ~Koma.PROMOTE;
    Te ret=new Te(koma,from,to,promote,capture);
    k.move(ret);
    return ret;
  }
  public void fireWinEvent(String cause) {
    System.out.println(cause+"により、");
    System.out.println("あなたの勝ち！");
    wait=false;
  }
  public void fireLoseEvent(String cause) {
    System.out.println(cause+"により、");
    System.out.println("あなたの負け！");
    wait=false;
  }
  public void fireDrawEvent(String cause) {
    System.out.println(cause+"により、");
    System.out.println("引き分け！");
    wait=false;
  }
  public void run() {
  try {
    PrintWriter pw=new PrintWriter(
        new OutputStreamWriter(new FileOutputStream("log.csa"),"MS932"));
    pw.println("N+"+nameSente);
    pw.println("N-"+nameGote);
    pw.println("P1-KY-KE-GI-KI-OU-KI-GI-KE-KY");
    pw.println("P2 * -HI *  *  *  *  * -KA * ");
    pw.println("P3-FU-FU-FU-FU-FU-FU-FU-FU-FU");
    pw.println("P4 *  *  *  *  *  *  *  *  * ");
    pw.println("P5 *  *  *  *  *  *  *  *  * ");
    pw.println("P6 *  *  *  *  *  *  *  *  * ");
    pw.println("P7+FU+FU+FU+FU+FU+FU+FU+FU+FU");
    pw.println("P8 * +KA *  *  *  *  * +HI * ");
    pw.println("P9+KY+KE+GI+KI+OU+KI+GI+KE+KY");
    pw.println("'先手番");
    pw.println("+");
    pw.flush();
    while(true) {
      String recv=ir.readLine();
      pw.println(recv);
      pw.flush();
      if (recv.equals("#SENNICHITE")) {
        // 次の１行は必ず#DRAWのはず。
        String result=ir.readLine();
        fireDrawEvent(recv);
        break;
      }
      if (recv.equals("#OUTE_SENNICHITE") || 
          recv.equals("#ILLEGAL_MOVE") ||
          recv.equals("#TIME_UP") ||
          recv.equals("#RESIGN") ||
          recv.equals("#JISHOGI")) {
        // 次の１行は必ず#WINか#LOSEのはず。
        String result=ir.readLine();
        if (result.equals("#WIN")) {
          fireWinEvent(recv);
        } else {
          fireLoseEvent(recv);
        }
        break;
      }
      // recvしたものが相手の手。
      if ((recv.startsWith("+") &&
            turn==GOTE) ||
          (recv.startsWith("-") &&
            turn==SENTE)
          ) {
          recieved=recv;
          int histime=0;
          try {
            histime=Integer.parseInt(recv.substring(recv.indexOf(",T")+2));
          }catch(NumberFormatException ne){
          }
          if (turn==SENTE) {
            time[1]=histime;
          } else {
            time[0]=histime;
          }
          wait=false;
      }
      // recvしたものが、自分の手。
      if ((recv.startsWith("-") &&
            turn==GOTE) ||
          (recv.startsWith("+") &&
            turn==SENTE)
          ) {
          int mytime=0;
          try {
            mytime=Integer.parseInt(recv.substring(recv.indexOf(",T")+2));
          }catch(NumberFormatException ne){
          }
          if (turn==SENTE) {
            time[0]=mytime;
          } else {
            time[1]=mytime;
          }
          waitTime=false;
      }
    }
    pw.close();
  }catch(Exception e) {
    e.printStackTrace();
  }
  }
}
// 各種定数の定義
interface Constants {
  // 「先手」の定義
  public final static int SENTE=1<<4;
  // 「後手」の定義
  public final static int GOTE =1<<5;
  // 筋を表す文字列の定義
  public final static String sujiStr[]={
    "","１","２","３","４","５","６","７","８","９"
  };
  // 段を表す文字列の定義
  public final static String danStr[]={
    "","一","二","三","四","五","六","七","八","九"
  };
}
class GenerateMoves implements Constants,KomaMoves {
  // 各手について、自分の玉に王手がかかっていないかどうかチェックし、
  // 王手がかかっている手は取り除く。
  public static Vector removeSelfMate(Kyokumen k,Vector v) {
    Vector removed=new Vector();
    for(int i=0;i<v.size();i++) {
      // 手を取り出す。
      Te te=(Te)v.elementAt(i);
      // その手で１手進めてみる
      Kyokumen test=(Kyokumen)k.clone();
      test.move(te);
      // 自玉を探す
      int gyokuPosition=test.searchGyoku(k.teban);
      // 王手放置しているかどうかフラグ
      boolean isOuteHouchi=false;
      // 玉の周辺（１２方向）から相手の駒が利いていたら、その手は取り除く
      for(int direct=0;direct<12 && !isOuteHouchi;direct++) {
        // 方向の反対方向にある駒を取得
        int pos=gyokuPosition;
        pos-=diff[direct];
        int koma=test.get(pos);
        // その駒が敵の駒で、玉方向に動けるか？
        if (Koma.isEnemy(test.teban,koma) && canMove[direct][koma]) {
          // 動けるなら、この手は王手を放置しているので、
          // この手は、removedに追加しない。
          isOuteHouchi=true;
          break;
        }
      }
      // 玉の周り（８方向）から相手の駒の飛び利きがあるなら、その手は取り除く
      for(int direct=0;direct<8 && !isOuteHouchi;direct++) {
        // 方向の反対方向にある駒を取得
        int pos=gyokuPosition;
        int koma;
        // その方向にマスが空いている限り、駒を探す
        for(pos-=diff[direct],koma=test.get(pos);
        koma!=Koma.WALL;pos-=diff[direct],koma=test.get(pos)) {
          // 味方駒で利きが遮られているなら、チェック終わり。
          if (Koma.isSelf(test.teban,koma)) break;
          // 遮られていない相手の駒の利きがあるなら、王手がかかっている。
          if (Koma.isEnemy(test.teban,koma) && canJump[direct][koma]) {
            isOuteHouchi=true;
            break;
          }
          // 敵駒で利きが遮られているから、チェック終わり。
          if (Koma.isEnemy(test.teban,koma)) {
            break;
          }
        }
      }
      if (!isOuteHouchi) {
        removed.add(te);
      }
    }
    return removed;
  }
  // 与えられたVectorに、手番、駒の種類、移動元、移動先を考慮して、
  // 成る・不成りを判断しながら生成した手を追加する。
  public static void addTe(Kyokumen k,Vector v,int teban,int koma,int from,int to) {
    if (teban==SENTE) {
      // 先手番
      if ((Koma.getKomashu(koma)==Koma.KY || Koma.getKomashu(koma)==Koma.FU) && (to&0x0f)==1) {
        // 香車か歩が１段目に進むときには、成ることしか選べない。
        Te te=new Te(koma,from,to,true,k.get(to));
        v.add(te);
      } else if (Koma.getKomashu(koma)==Koma.KE && (to&0x0f)<=2) {
        // 桂馬が２段目以上に進む時には、成ることしか選べない。
        Te te=new Te(koma,from,to,true,k.get(to));
        v.add(te);
      } else if ( ( (to&0x0f)<=3 || (from&0x0f)<=3 ) && Koma.canPromote(koma)) {
        // 駒の居た位置が相手陣か、進む位置が相手陣で、
        // 駒が成ることが出来るなら
        // 成りと不成りの両方の手を生成
        Te te=new Te(koma,from,to,true,k.get(to));
        v.add(te);
        te=new Te(koma,from,to,false,k.get(to));
        v.add(te);
      } else {
        // 不成りの手のみ生成
        Te te=new Te(koma,from,to,false,k.get(to));
        v.add(te);
      }
    } else {
      // 後手番
      if ((Koma.getKomashu(koma)==Koma.KY || Koma.getKomashu(koma)==Koma.FU) && (to&0x0f)==9) {
        // 香車か歩が九段目に進むときには、成ることしか選べない。
        Te te=new Te(koma,from,to,true,k.get(to));
        v.add(te);
      } else if (Koma.getKomashu(koma)==Koma.KE && (to&0x0f)>=8) {
        // 桂馬が八段目以上に進む時には、成ることしか選べない。
        Te te=new Te(koma,from,to,true,k.get(to));
        v.add(te);
      } else if ( ( (to&0x0f)>=7 || (from&0x0f)>=7 ) && Koma.canPromote(koma)) {
        // 駒の居た位置が相手陣か、進む位置が相手陣で、
        // 駒が成ることが出来るなら
        // 成りと不成りの両方の手を生成
        Te te=new Te(koma,from,to,true,k.get(to));
        v.add(te);
        te=new Te(koma,from,to,false,k.get(to));
        v.add(te);
      } else {
        // 不成りの手のみ生成
        Te te=new Te(koma,from,to,false,k.get(to));
        v.add(te);
      }
    }
  }
  // 打ち歩詰めになっていないかどうかチェックする関数
  // 相手の玉頭に歩を打つ場合、
  // その手で一手進めてみて、相手の手番でGenerateLegalMoveを行い、
  // 帰ってくる手がなかったなら打ち歩詰めになっている。
  public static boolean isUtiFuDume(Kyokumen k,Te te) {
    if (te.from!=0) {
      // 駒を打つ手ではないので、打ち歩詰めではない。
      return false;
    }
    if (Koma.getKomashu(te.koma)!=Koma.FU) {
      // 歩を打つ手ではないので、打ち歩詰めではない。
      return false;
    }
    int teban;
    int tebanAite;
    if ((te.koma&SENTE)!=0) {
      // 先手の歩を打つから、自分の手番は先手、相手の手番は後手
      teban=SENTE;
      tebanAite=GOTE;
    } else {
      // そうでない時は、自分の手番は後手、相手の手番は先手
      teban=GOTE;
      tebanAite=SENTE;
    }
    int gyokuPositionAite=k.searchGyoku(tebanAite);
    if (teban==SENTE) {
      if (gyokuPositionAite!=te.to-1) {
        // 相手の玉の頭に歩を打つ手ではないので、打ち歩詰めになっていない。
        return false;
      }
    } else {
      if (gyokuPositionAite!=te.to+1) {
        // 相手の玉の頭に歩を打つ手ではないので、打ち歩詰めになっていない。
        return false;
      }
    }
    // 実際に一手進めてみる…。
    Kyokumen test=(Kyokumen)k.clone();
    test.move(te);
    test.teban=tebanAite;
    // その局面で、相手に合法手があるか？なければ、打ち歩詰め。
    Vector v=generateLegalMoves(test);
    if (v.size()==0) {
      // 合法手がないので、打ち歩詰め。
      return true;
    }
    return false;
  }
  // 与えられた局面における合法手を生成する。
  public static Vector generateLegalMoves(Kyokumen k) {
    return generateLegalMoves(k,16);
  }
  public static Vector generateLegalMoves(Kyokumen k,int remainDepth) {
    Vector v=new Vector();
    // 盤上の手番の側の駒を動かす手を生成
    for(int suji=0x10;suji<=0x90;suji+=0x10) {
      for(int dan=1;dan<=9;dan++) {
        int from=dan+suji;
        int koma=k.get(from);
        // 自分の駒であるかどうか確認
        if (Koma.isSelf(k.teban,koma)) {
          // 各方向に移動する手を生成
          for(int direct=0;direct<12;direct++) {
            if (canMove[direct][koma]) {
              // 移動先を生成
              int to=from+diff[direct];
              // 移動先は盤内か？
              if (1<=(to>>4) && (to>>4)<=9 && 1<=(to&0x0f) && (to&0x0f)<=9) {
                // 移動先に自分の駒がないか？
                if (Koma.isSelf(k.teban,k.get(to))) {
                  // 自分の駒だったら、次の方向を検討
                  continue;
                }
                // 成る・不成りを考慮しながら、手をvに追加
                addTe(k,v,k.teban,koma,from,to);
              }
            }
          }
          // 各方向に「飛ぶ」手を生成
          for(int direct=0;direct<8;direct++) {
            if (canJump[direct][koma]) {
              // そちら方向に飛ぶことが出来る
              for(int i=1;i<9;i++) {
                // 移動先を生成
                int to=from+diff[direct]*i;
                // 行き先が盤外だったら、そこには行けない
                if (k.get(to)==Koma.WALL) break;
                // 行き先に自分の駒があったら、そこには行けない
                if (Koma.isSelf(k.teban,k.get(to))) break;
                // 成る・不成りを考慮しながら、手をvに追加
                addTe(k,v,k.teban,koma,from,to);
                // 空き升でなければ、ここで終わり
                if (k.get(to)!=Koma.EMPTY) break;
              }
            }
          }
        }
      }
    }
    int gyokuPosition=k.searchGyoku(k.teban);
    boolean isOute=false;
    // 玉の周辺（１２方向）から相手の駒が利いていたら、その手は取り除く
    for(int direct=0;direct<12 && !isOute;direct++) {
      // 方向の反対方向にある駒を取得
      int pos=gyokuPosition;
      pos-=diff[direct];
      int koma=k.get(pos);
      // その駒が敵の駒で、玉方向に動けるか？
      if (Koma.isEnemy(k.teban,koma) && canMove[direct][koma]) {
        // 動けるなら、この手は王手を放置しているので、
        // この手は、removedに追加しない。
        isOute=true;
        break;
      }
    }
    // 玉の周り（８方向）から相手の駒の飛び利きがあるなら、その手は取り除く
    for(int direct=0;direct<8 && !isOute;direct++) {
      // 方向の反対方向にある駒を取得
      int pos=gyokuPosition;
      int koma;
      // その方向にマスが空いている限り、駒を探す
      for(pos-=diff[direct],koma=k.get(pos);
      koma!=Koma.WALL;pos-=diff[direct],koma=k.get(pos)) {
        // 味方駒で利きが遮られているなら、チェック終わり。
        if (Koma.isSelf(k.teban,koma)) break;
        // 遮られていない相手の駒の利きがあるなら、王手がかかっている。
        if (Koma.isEnemy(k.teban,koma) && canJump[direct][koma]) {
          isOute=true;
          break;
        }
        // 敵駒で利きが遮られているから、チェック終わり。
        if (Koma.isEnemy(k.teban,koma)) {
          break;
        }
      }
    }
    // 手番の側の駒を打つ手を生成
    // 残り深さ１以下で、王手がかかっていないなら、駒を打つ手は生成しない。
    if (remainDepth<=1 && !isOute) {
    } else {
      // 駒を打つ手の前向き枝刈りに使用
      Te teS[]=new Te[20];
      Te teE[]=new Te[20];
      if (remainDepth<3) {
          for(int i=0;i<20;i++) {
            teS[i]=new Te();
            teE[i]=new Te();
          }
      }
      // まず、駒の種類でループ
      for(int i=Koma.FU;i<=Koma.HI;i++) {
        // 打つ駒は、手番の側の駒
        int koma=i|k.teban;
        // その駒を持っているか？
        if (k.hand[koma]>0) {
          // 持っている。
          int komashu=Koma.getKomashu(koma);
          // 盤面の各升目でループ
          for(int suji=0x10;suji<=0x90;suji+=0x10) {
            // 二歩にならないかどうかチェック
            if (komashu==Koma.FU) {
              // 二歩のチェック用変数
              boolean isNifu=false;
              // 二歩チェック
              // 同じ筋に、手番の側の歩がいないことを確認する
              for(int dan=1;dan<=9;dan++) {
                int p=suji+dan;
                // 手番の側の歩が、同じ筋にいないかどうかチェックする
                if (k.get(p)==(k.teban|Koma.FU)) {
                  // 二歩になっている。
                  isNifu=true;
                  break;
                }
              }
              if (isNifu) {
                // 二歩になっているので、打つ手を生成しない。
                // 次の筋へ
                continue;
              }
            }
            for(int dan=1;dan<=9;dan++) {
              // 駒が桂馬の場合の扱い
              if (komashu==Koma.KE) {
                if (k.teban==SENTE && dan<=2) {
                  // 先手なら、二段目より上に桂馬は打てない
                  continue;
                } else if (k.teban==GOTE && dan>=8) {
                  // 後手なら、八段目より下に桂馬は打てない
                  continue;
                }
              }
              // 駒が歩、または香車の場合の扱い
              if (komashu==Koma.FU || komashu==Koma.KY) {
                if (k.teban==SENTE && dan==1) {
                  // 先手なら、一段目に歩と香車は打てない
                  continue;
                } else if (k.teban==GOTE && dan==9) {
                  // 後手なら、九段目に歩と香車は打てない
                  continue;
                }
              }
              // 移動元…駒を打つ手は、0
              int from=0;
              // 移動先、駒を打つ場所
              int to=suji+dan;
              // 空き升でなければ、打つ事は出来ない。
              if (k.get(to)!=Koma.EMPTY) {
                continue;
              }
              // 手の生成…駒を打つ際には、常に不成で、取る駒もなしである。
              Te te=new Te(koma,from,to,false,Koma.EMPTY);
              // 打ち歩詰めの特殊扱い
              if (isUtiFuDume(k,te)) {
                // 打ち歩詰めなら、そこに歩は打てない
                continue;
              }
              // 駒を打つとすぐに取られるような手は合法手として生成しない。
              if (!isOute && remainDepth<3) {
                k.move(te);
                if (EvalPos(k,te.to,k.teban,teS,teE)>400){
                  // 駒損する。
                  k.back(te);
                  continue;
                }
                k.back(te);
              }
              // 駒を打つ手が可能なことが分かったので、合法手に加える。
              v.add(te);
            }
          }
        }
      }
    }
    // 生成した各手について、指してみて
    // 自分の玉に王手がかかっていないかどうかチェックし、
    // 王手がかかっている手は取り除く。
    v=removeSelfMate(k,v);
    return v;
  }
  // ２つのものから最大を返す
  static int max(int x,int y) {
    if (x>y) {
      return x;
    } else {
      return y;
    }
  }
  // ２つのものから最小を返す
  static int min(int x,int y) {
    if (x<y) {
      return x;
    } else {
      return y;
    }
  }
  // 絶対値を返す
  static int abs(int x) {
    if (x<0) {
      return -x;
    } else {
      return x;
    }
  }
  // ある位置からある位置への距離を求める。
  static int kyori(int p1,int p2) {
    return max(abs(p1/16-p2/16),abs((p1 & 0x0f)-(p2 &0x0f)));
  }
  // 交換値を求める際に、正しい手かどうかチェックする。
  // captureが入っていない場合、captureを埋める。
  static boolean IsCorrectMove(Kyokumen k,Te te) {
    if (k.ban[te.from]==Koma.SKE || k.ban[te.from]==Koma.GKE) {
      te.capture=k.ban[te.to];
      return true;
    }
    int d=kyori(te.from,te.to);
    if (d==0) return false;
    int dir=(te.to-te.from)/d;
    if (d==1) {
      te.capture=k.ban[te.to];
      return true;
    }
    // 距離が２以上ならば、
    // ジャンプなので、途中に邪魔な駒がいないかどうかチェックする
    for(int i=1,pos=te.from+dir;i<d;i++,pos=pos+dir) {
      if (k.ban[pos]!=Koma.EMPTY) {
        return false;
      }
    }
    te.capture=k.ban[te.to];
    return true;
  }
  // ある地点における駒の取り合いの探索
  // 後手側ノード
  static int EvalMin(Kyokumen k,Te AtackS[],int NowAtackS,int NumAtackS,
      Te AtackE[],int NowAtackE,int NumAtackE) {
    int v=k.eval;
    int oldEval=k.eval;
    if (NumAtackE>NowAtackE) {
      // 邪魔駒の処理
      int j=NowAtackE;
      // その動きが正しいか？
      // 例えば、香車が前の駒を追い越したりしていないか？
      while(j<NumAtackE && !IsCorrectMove(k,AtackE[j])) {
        j++;
      }
      if (j==NowAtackE) {
        // 予定していた動きでＯＫ
      } else if (j<NumAtackE) {
        // 予定していた動きがＮＧで、別の動きと入れ替え
        Te t=AtackE[j];
        for(int i=j;i>NowAtackE;i--) {
          AtackE[i]=AtackE[i-1];
        }
        AtackE[NowAtackE]=t;
      } else {
        // 他に手がない＝取れない。
        return v;
      }
      AtackE[NowAtackE].capture=k.ban[AtackE[NowAtackE].to];
      k.move(AtackE[NowAtackE]);
      v=min(v,EvalMax(k,AtackS,NowAtackS,NumAtackS,
          AtackE,NowAtackE+1,NumAtackE));
      k.back(AtackE[NowAtackE]);
    }
    return v;
  }
  // ある地点における駒の取り合いの探索
  // 先手側ノードの探索
  static int EvalMax(Kyokumen k,Te AtackS[],int NowAtackS,int NumAtackS,
      Te AtackE[],int NowAtackE,int NumAtackE) {
    int v=k.eval;
    if (NumAtackS>NowAtackS) {
      // 邪魔駒の処理
      int j=NowAtackS;
      // その動きが正しいか？
      // 例えば、香車が前の駒を追い越したりしていないか？
      while(j<NumAtackS && !IsCorrectMove(k,AtackS[j])) {
        j++;
      }
      if (j==NowAtackS) {
        // 予定していた動きでＯＫ
      } else if (j<NumAtackS) {
        // 予定していた動きがＮＧで、別の動きと入れ替え
        Te t=AtackS[j];
        for(int i=j;i>NowAtackS;i--) {
          AtackS[i]=AtackS[i-1];
        }
        AtackS[NowAtackS]=t;
      } else {
        // 他に手がない＝取れない。
        return v;
      }
      AtackS[NowAtackS].capture=k.ban[AtackS[NowAtackS].to];
      k.move(AtackS[NowAtackS]);
      v=max(v,EvalMin(k,AtackS,NowAtackS+1,NumAtackS,
          AtackE,NowAtackE,NumAtackE));
      k.back(AtackS[NowAtackS]);
    }
    return v;
  }
  // 速度的にはまずいのだけれど、同時に２思考が走る場合に、staticに
  // しているとちょっとまずい…。
  // ある位置での駒の取り合い探索用の手の準備
  // 先手側
  /*
  static Te teS[]={
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te()
  };
  // 後手側
  static Te teE[]={
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te()
  };
  */
  // 与えられた局面kの与えられた位置 positionで、手番SorGが
  // 駒交換を行った場合に、何点を得られるか？を求める。
  // これも、ＭｉｎＭａｘ法である。
  static int EvalPos(Kyokumen k,int position,int SorG,Te teS[],Te teE[]) {
    int ret=0;
    int ToPos=position;
    // AtackCountを得るように、駒のリストを得る
    // 一個所への利きは、最大隣接8+桂馬2+飛飛角角香香香香=18だから、
    // ２０あれば十分。
    // AtackS,AtackCountSが先手側のその位置に動く手
    // AtackE,AtackCountEが後手側のその位置に動く手
    Te AtackS[]=teS;
    Te AtackE[]=teE;
    int AtackCountE=0;
    int AtackCountS=0;
    int pos2;
    // 先手側成りフラグ、後手側成りフラグ
    boolean PromoteS,PromoteE;
    int i;
    int pos=ToPos;
    if ((ToPos&0x0f)<=3) {
      PromoteS=true;
    } else {
      PromoteS=false;
    }
    if ((ToPos&0x0f)>=7) {
      PromoteE=true;
    } else {
      PromoteE=false;
    }
    // 桂馬の利きは別に数える
    for (i = 0; i < 8; i++) {
      // 周り８方向の位置がpos2に入る
      pos2=pos-diff[i];
      // pos2にあるのが、壁ならcontinue
      if (k.ban[pos2]==Koma.WALL) {
        continue;
      }
      if (canMove[i][k.ban[pos2]] && Koma.isSente(k.ban[pos2])) {
        // pos2にある駒がposの側に動く事が出来る先手の駒なら…
        // AtackS,AtackCountSに、その駒を動かす手を入れる。
        AtackS[AtackCountS].from=pos2;
        AtackS[AtackCountS].koma=k.ban[pos2];
        AtackS[AtackCountS].to=pos;
        if ((PromoteS || (pos2 & 0x0f)<=3) && Koma.canPromote[AtackS[AtackCountS].koma]) {
          AtackS[AtackCountS].promote=true;
        } else {
          AtackS[AtackCountS].promote=false;
        }
        AtackCountS++;
      } else if (canMove[i][k.ban[pos2]] && Koma.isGote(k.ban[pos2])) {
        // pos2にある駒がposの側に動く事が出来る後手の駒なら…
        // AtackE,AtackCountEに、その駒を動かす手を入れる。
        AtackE[AtackCountE].from=pos2;
        AtackE[AtackCountE].koma=k.ban[pos2];
        AtackE[AtackCountE].to=pos;
        if ((PromoteE || (pos2 & 0x0f)>=7) && Koma.canPromote[AtackE[AtackCountE].koma]) {
          AtackE[AtackCountE].promote=true;
        } else {
          AtackE[AtackCountE].promote=false;
        }
        AtackCountE++;
      }
      // 玉以外の駒は貫き通せることにしておく。
      if (k.ban[pos-diff[i]]!=Koma.SOU && k.ban[pos-diff[i]]!=Koma.GOU) {
        // 今度は、pos2をposにもう一度戻し、
        pos2=pos;
        // その方向に飛び利きのある駒を探していく。
        while(k.ban[pos2]!=Koma.WALL) {
          pos2-=diff[i];
          while(k.ban[pos2]==Koma.EMPTY) {
            pos2-=diff[i];
          }
          if (k.ban[pos2]==Koma.WALL) {
            break;
          }
          if (!canJump[i][k.ban[pos2]]) {
            break;
          }
          if (Koma.isSente(k.ban[pos2])) {
            AtackS[AtackCountS].from=pos2;
            AtackS[AtackCountS].koma=k.ban[pos2];
            AtackS[AtackCountS].to=pos;
            if ((PromoteS || (pos2 & 0x0f)<=3) && Koma.canPromote[AtackS[AtackCountS].koma]) {
              AtackS[AtackCountS].promote=true;
            } else {
              AtackS[AtackCountS].promote=false;
            }
            AtackCountS++;
          } else if (Koma.isGote(k.ban[pos2])) {
            AtackE[AtackCountE].from=pos2;
            AtackE[AtackCountE].koma=k.ban[pos2];
            AtackE[AtackCountE].to=pos;
            if ((PromoteE || (pos2 & 0x0f)>=7) && Koma.canPromote[AtackE[AtackCountE].koma]) {
              AtackE[AtackCountE].promote=true;
            } else {
              AtackE[AtackCountE].promote=false;
            }
            AtackCountE++;
          }
        }
      }
    }
    // 桂馬の利き
    for(i=8;i<12;i++) {
      pos2=pos-diff[i];
      if (pos2<0 || k.ban[pos2]==Koma.WALL) {
        continue;
      }
      if (canMove[i][k.ban[pos2]] && Koma.isSente(k.ban[pos2])) {
        AtackS[AtackCountS].from=pos2;
        AtackS[AtackCountS].koma=k.ban[pos2];
        AtackS[AtackCountS].to=pos;
        if ((PromoteS || (pos2 & 0x0f)<=3) && Koma.canPromote[AtackS[AtackCountS].koma]) {
          AtackS[AtackCountS].promote=true;
        } else {
          AtackS[AtackCountS].promote=false;
        }
        AtackCountS++;
      } else if (canMove[i][k.ban[pos2]] && Koma.isGote(k.ban[pos2])) {
        AtackE[AtackCountE].from=pos2;
        AtackE[AtackCountE].koma=k.ban[pos2];
        AtackE[AtackCountE].to=pos;
        if ((PromoteE || (pos2 & 0x0f)>=7) && Koma.canPromote[AtackE[AtackCountE].koma]) {
          AtackE[AtackCountE].promote=true;
        } else {
          AtackE[AtackCountE].promote=false;
        }
        AtackCountE++;
      }
    }
    // AtackSを駒の価値でソート。
    for (i=0; i < AtackCountS-1; i++) {
      int max_id = i; int max_val = k.komaValue[AtackS[i].koma];
      for (int j = i+1; j < AtackCountS ; j++) {
        int v=k.komaValue[AtackS[j].koma];
        if (v < max_val) {
          max_id = j;
          max_val= v;
        } else if (v==max_val) {
          if (k.komaValue[AtackS[j].koma]<k.komaValue[AtackS[max_id].koma]) {
            max_id=j;
          }
        }
      }
      //最大値との交換
      if (i!=max_id) {
        Te temp=AtackS[i];
        AtackS[i]=AtackS[max_id];
        AtackS[max_id]=temp;
      }
    }
    // AtackEを駒の価値でソート。
    for (i=0; i < AtackCountE-1; i++) {
      int max_id = i; int max_val = k.komaValue[AtackE[i].koma];
      for (int j = i+1; j < AtackCountE ; j++) {
        int v=k.komaValue[AtackE[j].koma];
        if (v> max_val) {
          max_id = j;
          max_val= v;
        } else if (v==max_val) {
          if (k.komaValue[AtackE[j].koma]>k.komaValue[AtackE[max_id].koma]) {
            max_id=j;
          }
        }
      }
      //最大値との交換
      if (i!=max_id) {
        Te temp=AtackE[i];
        AtackE[i]=AtackE[max_id];
        AtackE[max_id]=temp;
      }
    }
    boolean IsGote=Koma.isGote(k.ban[position]);
    boolean IsSente=Koma.isSente(k.ban[position]);
    if (k.ban[position]==Koma.EMPTY) {
      if (SorG==SENTE) {
        IsGote=true;
      } else {
        IsSente=true;
      }
    }
    try {
      if (IsGote && AtackCountS>0) {
        ret=EvalMax(k,AtackS,0,AtackCountS,AtackE,0,AtackCountE)-k.eval;
      } else if (IsSente && AtackCountE>0) {
        ret=k.eval-EvalMin(k,AtackS,0,AtackCountS,AtackE,0,AtackCountE);
      } else {
        ret=0;
      }
    } catch(Exception ex) {
      for(int d=0;d<AtackCountS;d++) {
        System.out.println("AtackS:");
        System.out.print(AtackS[d]);
        System.out.println();
      }
      for(int d=0;d<AtackCountE;d++) {
        System.out.println("AtackE:");
        System.out.print(AtackE[d]);
        System.out.println();
      }
    }
    return ret;
  }
  // 駒を動かした際に変化する地点での交換値を元に、
  // 手の価値を計算して、ソートしてみる。
  public static void evaluateTe(Kyokumen k,Vector v,Te teS[],Te teE[]) {
    // 現在の局面の評価値
    int nowEval=k.eval;
    // 相手玉
    int EnemyKing;
    if (k.teban==SENTE) {
      EnemyKing=Koma.GOU;
    } else {
      EnemyKing=Koma.SOU;
    }
    // 全部の手について…
    for(int i=0;i<v.size();i++) {
      // 手を取り出す。
      Te te=(Te)v.elementAt(i);
      // GainSPosは、GainSを元の局面で計算しないとならない位置
      int GainSPos[]=new int [30];
      int GainSNum=0;
      // LossSが自分から見た、駒損をする脅威
      // LossEが相手から見た、駒損をする脅威
      // GainSが自分から見た、駒損をする脅威
      // GainEが相手から見た、駒得をする脅威（計算していない。）
      int LossS,LossE,GainS,GainE;
      LossS=LossE=GainS=GainE=0;
      // 成った後の駒を覚えておく。
      int newKoma=te.koma;
      if (te.promote) newKoma|=Koma.PROMOTE;
      // その手で進めてみる
      k.move(te);
      // 手の仮の価値は、元の局面との評価値の差分
      te.value=k.eval-nowEval;
      // 移動先での脅威
      LossS+=EvalPos(k,te.to,k.teban,teS,teE);
      // 相手に与える脅威と、新しく自分の駒にヒモをつけることで、減る脅威を計算
      for(int dir=0;dir<12;dir++) {
        // 動かした駒が12方向へ、動くことを計算
        if (canMove[dir][newKoma]) {
          // その方向に動けるなら…
          int p=te.to+diff[dir];
          // 玉じゃないのなら、
          if (k.ban[p]!=EnemyKing) {
            if (Koma.isEnemy(k.teban,k.ban[p])) {
              // 相手の駒なら、相手から見た駒損をする脅威
              LossE+=EvalPos(k,p,k.teban,teS,teE);
            } else if (Koma.isSelf(k.teban,k.ban[p])) {
              // 自分の駒なら、自分から見た駒損をする脅威
              GainS-=EvalPos(k,p,k.teban,teS,teE);
              GainSPos[GainSNum++]=p; // この地点の元の脅威を後で計算する
            }
          } else {
            // 玉に与える脅威は1000点で計算しておく。
            LossE+=1000;
          }
        }
      }
      if (te.from!=0) {
        // 他の駒の飛び利きを通した？
        for(int dir=0;dir<8;dir++) {
          // その駒から「逆」方向へ…
          int pos=te.from-diff[dir];
          while(k.ban[pos]==Koma.EMPTY) {
            pos-=diff[dir];
          }
          // 何か見つかった。
          // それが、この方向に飛び利きを持つ駒なら…
          if(k.ban[pos]!=Koma.WALL && canJump[dir][k.ban[pos]]) {
            pos=te.from+diff[dir];
            while(k.ban[pos]==Koma.EMPTY){
              pos+=diff[dir];
            }
            // 飛び利きの通った先の交換値を求める。
            if (k.ban[pos]!=Koma.WALL){
              if (Koma.isEnemy(k.teban,k.ban[pos])) {
                LossE+=EvalPos(k,pos,k.teban,teS,teE);
              } else if (Koma.isSelf(k.teban,k.ban[pos])) {
                GainS-=EvalPos(k,pos,k.teban,teS,teE);
                GainSPos[GainSNum++]=pos; // 元の脅威を後で計算する
              }
            }
          }
        }
      }
      // 同様に、８方向の飛び利きを求める。
      for(int dir=0;dir<8;dir++) {
        if (canJump[dir][newKoma]) {
          int p=te.to+diff[dir];
          while(k.ban[p]==Koma.EMPTY) {
            p+=diff[dir];
          }
          // 例によって、玉に対する脅威は大きく評価されすぎるので調整する。
          if (k.ban[p]!=EnemyKing) {
            if (Koma.isEnemy(k.teban,k.ban[p])) {
              LossE+=EvalPos(k,p,k.teban,teS,teE);
            } else if (Koma.isSelf(k.teban,k.ban[p])) {
              GainS+=-EvalPos(k,p,k.teban,teS,teE);
              GainSPos[GainSNum++]=p; // 元の脅威を後で計算する
            }
          } else {
            // 玉に与える脅威は1000点で計算しておく。
            LossE+=1000;
          }
        }
      }
      // 局面を元に戻す。
      k.back(te);
      // 後手番なら、先に求めた評価値の差分が、正負が逆になるので…
      if (k.teban==GOTE) {
        te.value=-te.value;
      }
      // 元の脅威について、後で計算しないとならない点について、
      // 溜め込んでおいた情報に基づいて計算
      for(int j=0;j<GainSNum;j++) {
        GainS+=EvalPos(k,GainSPos[j],k.teban,teS,teE);
      }
      // 駒を動かす手であれば…
      if (te.from!=0) {
        // 移動元にあった、脅威はなくなる
        LossS-=EvalPos(k,te.from,k.teban,teS,teE);
      }
      // GainS,LossSは、そのまま手の価値に加算してしまう。
      te.value+=GainS-LossS;
      te.value2=te.value;
      // 駒を取る手が読みに入りやすくするように、点数を加算する。
      if (te.capture!=Koma.EMPTY &&
          te.capture!=Koma.SFU && te.capture!=Koma.GFU) {
        // 歩以外の駒を取る手は無条件に1500点プラスして、読みに入れるようにする
        te.value+=1500;
      }
      // 相手に与える脅威は1/10位にして加算すると、実験上ちょうど良い。
      te.value+=LossE/10;
//    デバッグ用…出力すると、膨大になります。
//      System.out.print(te);
//      System.out.println("value:"+te.value+" GainS:"+GainS+
//          " GainE:"+GainE+" LossS:"+LossS+" LossE:"+LossE);
    }
    // ソートする。
    for(int i=0;i<v.size();i++) {
      Te te=(Te)v.elementAt(i);
      int maxValue=te.value;
      int maxIndex=i;
      for(int j=i+1;j<v.size();j++) {
        te=(Te)v.elementAt(j);
        if (te.value>maxValue) {
          maxValue=te.value;
          maxIndex=j;
        }
      }
      Te tmp=(Te)v.elementAt(maxIndex);
      Te old=(Te)v.elementAt(i);
      v.setElementAt(tmp,i);
      v.setElementAt(old,maxIndex);
    }
//    デバッグ用…出力すると、膨大になります。
    /*
    for(int i=0;i<v.size();i++) {
      Te te=(Te)v.elementAt(i);
      System.out.print(te);
      System.out.println(te.value);
    }
     */
  }
  public static boolean isLegalMove(Kyokumen k,Te t) {
    if (t.from>0 && k.ban[t.from]!=t.koma) {
      // 移動元の駒が違う
      return false;
    }
    if (t.from==0 && k.hand[t.koma]==0) {
      // 持ち駒に持っていない
      return false;
    }
    if (t.from==0 && k.ban[t.to]!=Koma.EMPTY) {
      // 空いてないので打てない
      return false;
    }
    if (Koma.isSelf((t.koma & (SENTE|GOTE)),k.ban[t.to])) {
      // 自分の駒のあるところには進めない
      return false;
    }
    if (isUtiFuDume(k,t)) {
      // 打ち歩詰め
      return false;
    }
    if (!IsCorrectMove(k,t)) {
      return false;
    }
    // 王手放置になっていないかどうかチェック
    // その手で１手進めてみる
    Kyokumen test=(Kyokumen)k.clone();
    test.move(t);
    // 自玉を探す
    int gyokuPosition=test.searchGyoku(k.teban);
    // 王手放置しているかどうかフラグ
    boolean isOuteHouchi=false;
    // 玉の周辺（１２方向）から相手の駒が利いていたら、その手は取り除く
    for(int direct=0;direct<12 && !isOuteHouchi;direct++) {
      // 方向の反対方向にある駒を取得
      int pos=gyokuPosition;
      pos-=diff[direct];
      int koma=test.get(pos);
      // その駒が敵の駒で、玉方向に動けるか？
      if (Koma.isEnemy(test.teban,koma) && canMove[direct][koma]) {
        // 動けるなら、この手は王手を放置しているので、
        // この手は、removedに追加しない。
        isOuteHouchi=true;
        break;
      }
    }
    // 玉の周り（８方向）から相手の駒の飛び利きがあるなら、その手は取り除く
    for(int direct=0;direct<8 && !isOuteHouchi;direct++) {
      // 方向の反対方向にある駒を取得
      int pos=gyokuPosition;
      int koma;
      // その方向にマスが空いている限り、駒を探す
      for(pos-=diff[direct],koma=test.get(pos);
      koma!=Koma.WALL;pos-=diff[direct],koma=test.get(pos)) {
        // 味方駒で利きが遮られているなら、チェック終わり。
        if (Koma.isSelf(test.teban,koma)) break;
        // 遮られていない相手の駒の利きがあるなら、王手がかかっている。
        if (Koma.isEnemy(test.teban,koma) && canJump[direct][koma]) {
          isOuteHouchi=true;
          break;
        }
        // 敵駒で利きが遮られているから、チェック終わり。
        if (Koma.isEnemy(test.teban,koma)) {
          break;
        }
      }
    }
    if (isOuteHouchi) {
      return false;
    }
    return true;
  }
  // 軽く手を生成してみる。
  public static Vector makeMoveFirst(
      Kyokumen k,int depth,Sikou s,TTEntry e) {
    Vector v=new Vector();
    if (e!=null && e.best!=null && isLegalMove(k,e.best)) {
      v.add(e.best);
    }
    if (depth>0 && s.best[depth-1][depth]!=null &&
        isLegalMove(k,s.best[depth-1][depth])){
      v.add(s.best[depth-1][depth]);
    }
    if (e!=null && e.second!=null && isLegalMove(k,e.second)) {
      v.add(e.second);
    }
    return v;
  }
}
class Human implements Player,Constants {
  // 一行入力用の読み込み元を用意しておく。
  // static メンバー変数で用意しておくのは、人間対人間の
  // 対戦時に、同じ読み込み元を使いたいため。
  // こうすることで、標準入力にファイルを使用できる。
  static BufferedReader reader=
    new BufferedReader(new InputStreamReader(System.in));
  public Te getNextTe(Kyokumen k,int tesu,int spenttime,int limittime,int byoyomi) {
    // 現在の局面での合法手を生成
    Vector v=GenerateMoves.generateLegalMoves(k);
    // 返却する手の初期化…「投了」にあたるような、
    // 合法手でない手を生成しておく。
    Te te=new Te(0,0,0,false,0);
    do {
      if (k.teban==SENTE) {
        System.out.println("先手番です。");
      } else {
        System.out.println("後手番です。");
      }
      System.out.println("指し手を入力して下さい。");
      // 一行入力
      String s="";
      try {
        s=reader.readLine();
      }catch(Exception e){
        // 読み込みエラー？
        e.printStackTrace();
        break;
      }
      // 入力された手が%TORYOだったら、投了して終わり。
      if (s.equals("%TORYO")) {
        break;
      }
      if (s.equals("p")) {
        // 合法手の一覧と局面を出力。
        for(int i=0;i<v.size();i++) {
          Te t=(Te)v.elementAt(i);
          System.out.println(t);
        }
        System.out.println(k);
        continue;
      }
      boolean promote=false;
      if (s.length()==5) {
        if (s.substring(4,5).equals("*")) {
          // ５文字目が'*'だったら、『成り』
          promote=true;
        } else {
          // 何かおかしい…。
          System.out.println("入力が異常です。");
          // 局面を表示して、再入力を求める。
          System.out.println(k);
          continue;
        }
      }
      int fromSuji=0,fromDan=0,toSuji=0,toDan=0;
      try {
        fromSuji=Integer.parseInt(s.substring(0,1));
        fromDan =Integer.parseInt(s.substring(1,2));
        toSuji  =Integer.parseInt(s.substring(2,3));
        toDan   =Integer.parseInt(s.substring(3,4));
      }catch(Exception e){
        // 数値として読み込めなかったので、何か間違っている。
        System.out.println("手を読み込めませんでした。");
        System.out.println(""+fromSuji+""+fromDan+""+toSuji+""+toDan);
        // 局面を表示して、再入力を求める。
        System.out.println(k);
        continue;
      }
      // 駒
      int koma=0;
      // 最初の一桁が０の場合、駒打ち
      if (fromSuji==0) {
        // この場合、二桁目に打つ駒の種類が入っている。
        // 駒は、手番の側の駒。
        koma=fromDan|k.teban;
        // fromDanをクリア。
        fromDan=0;
      }
      int from=fromSuji*16+fromDan;
      int to  =toSuji  *16+toDan;
      if (fromSuji!=0) {
        koma=k.get(from);
      }
      te=new Te(koma,from,to,promote,k.get(to));
      if (!v.contains(te)) {
        // 合法手でないので、何か間違っている…。
        System.out.println(te);
        System.out.println("合法手ではありません。");
        // 局面を再表示。
        System.out.println(k);
      }
    } while(!v.contains(te));
    return te;
  }
}
// 定跡データを読み込み、定跡にある局面であれば、そこで指された手を返す。
// 同一局面が複数ある場合、手の候補から乱数で選択する。
class Joseki implements Constants {
  // 定跡を
  Joseki child=null;
  byte [][] josekiData;
  int numJoseki;
  // 乱数の生成
  Random random;
  Kyokumen josekiKyokumen;
  public Joseki(String josekiFileName) {
    random=new Random();
    if (josekiFileName.indexOf(",")>=0) {
      // 「子」定跡として、","以降のファイルを読み込む。
      child=new Joseki(josekiFileName.substring(josekiFileName.indexOf(",")+1));
      // 自分自身の読み込む定跡ファイルは、,の前までのファイル名
      josekiFileName=josekiFileName.substring(0,josekiFileName.indexOf(",")-1);
    }
    // ファイルから定跡データを読み込む。
    try {
      File f=new File(josekiFileName);
      FileInputStream in=new FileInputStream(f);
      numJoseki=(int)(f.length()/512);
      josekiData=new byte[numJoseki][512];
      for(int i=0;i<numJoseki && in.read(josekiData[i])>0;i++) {
      }
    } catch(Exception e) {
      numJoseki=0;
    }
  }
  public Te josekiByteToTe(byte to,byte from,Kyokumen k) {
    // byteは-128〜127と符号付で扱いにくいため、符号を取ったintにする。
    int f=((int)from)& 0xff;
    int t=((int)to)  & 0xff;
    int koma=0;
    boolean promote=false;
    if (f>100) {
      // fが100以上なら、(手番の側の)駒を打つ手。
      koma=(f-100)|k.teban;
      f=0;
    } else {
      // fを、このプログラムの中で使う座標の方式へ変換
      int fs = (f - 1) % 9 + 1; // 筋
      int fd = (f + 8) / 9;     // 段
      f=fs*16+fd;
      // 実際の駒は、盤面から得る。
      koma=k.ban[f];
    }
    // tが100以上なら、成る手。
    if (t>100) {
      promote=true;
      t=t-100;
    }
    // tを、このプログラムの中で使う座標の方式へ変換
    int ts = (t - 1) % 9 + 1; // 筋
    int td = (t + 8) / 9;     // 段
    t=ts*16+td;
    // 手を作成。
    return new Te(koma,f,t,promote,k.ban[t]);
  }
  public Te fromJoseki(Kyokumen k,int tesu) {
    // tesuには、実際の手数が渡される（1手目から始まる）が、
    // 定跡のデータは0手目から始まるので、1ずらしておく。
    tesu=tesu-1;
    // 定跡にあった候補手を入れる
    Vector v=new Vector();
    // 定跡で進めてみた局面を作成する
    // この局面と、渡された局面を比較する。
    Kyokumen josekiKyokumen;
    josekiKyokumen=new Kyokumen();
    for(int i=0;i<numJoseki;i++) {
      // 平手で初期化する。
      // 駒落ちなどを指させるには、改良が必要。
      josekiKyokumen.initHirate();
      int j=0;
      for(j=0;j<tesu;j++) {
        if (josekiData[i][j*2]==(byte)0 || josekiData[i][j*2]==(byte)0xff) {
          break;
        }
        Te te=josekiByteToTe(josekiData[i][j*2],josekiData[i][j*2+1],josekiKyokumen);
        josekiKyokumen.move(te);
        if (josekiKyokumen.teban==SENTE) {
          josekiKyokumen.teban=GOTE;
        } else {
          josekiKyokumen.teban=SENTE;
        }
      }
      // 局面が一致するか？
      if (j==tesu && josekiKyokumen.equals(k)) {
        // 局面が一致した。定跡データから次の手を引き出す。
        if (josekiData[i][tesu*2]==(byte)0 || josekiData[i][tesu*2]==(byte)0xff) {
          // 局面は一致していたが、ここで指す手がなかった。
          continue;
        }
        // 候補手を作成
        Te te=josekiByteToTe(josekiData[i][tesu*2],josekiData[i][tesu*2+1],k);
        v.add(te);
      }
    }
    if (v.size()==0) {
      // 候補手がなかった。
      if (child!=null) {
        // 子定跡があるときは、その結果を返す。
        return child.fromJoseki(k,tesu);
      }
      // 候補手がなかったので、nullを返す。
      return null;
    } else {
      // 候補手の中からランダムで選択する。
      return (Te)v.elementAt(random.nextInt(v.size()));
    }
  }
}
// 駒
class Koma implements Constants,Cloneable {
  // 駒の種類の定義
  public static final int EMPTY=0;          // 「空」
  public static final int EMP=EMPTY;        // 「空」の別名。
  public static final int PROMOTE=8;        // 「成り」フラグ
  public static final int FU= 1;            // 「歩」
  public static final int KY= 2;            // 「香車」
  public static final int KE= 3;            // 「桂馬」
  public static final int GI= 4;            // 「銀」
  public static final int KI= 5;            // 「金」
  public static final int KA= 6;            // 「角」
  public static final int HI= 7;            // 「飛車」
  public static final int OU= 8;            // 「玉将」
  public static final int TO=FU+PROMOTE;    // 「と金」＝「歩」＋成り
  public static final int NY=KY+PROMOTE;    // 「成り香」＝「香車」＋成り
  public static final int NK=KE+PROMOTE;    // 「成り桂」＝「桂馬」＋成り
  public static final int NG=GI+PROMOTE;    // 「成り銀」＝「銀」＋成り
  public static final int UM=KA+PROMOTE;    // 「馬」＝「角」＋成り
  public static final int RY=HI+PROMOTE;    // 「竜」＝「飛車」＋成り
  public static final int SFU=SENTE+FU;     // 「先手の歩」＝「歩」＋「先手」
  public static final int SKY=SENTE+KY;     // 「先手の香」
  public static final int SKE=SENTE+KE;     // 「先手の桂」
  public static final int SGI=SENTE+GI;     // 「先手の銀」
  public static final int SKI=SENTE+KI;     // 「先手の金」
  public static final int SKA=SENTE+KA;     // 「先手の角」
  public static final int SHI=SENTE+HI;     // 「先手の飛」
  public static final int SOU=SENTE+OU;     // 「先手の玉」
  public static final int STO=SENTE+TO;     // 「先手のと金」
  public static final int SNY=SENTE+NY;     // 「先手の成香」
  public static final int SNK=SENTE+NK;     // 「先手の成桂」
  public static final int SNG=SENTE+NG;     // 「先手の成銀」
  public static final int SUM=SENTE+UM;     // 「先手の馬」
  public static final int SRY=SENTE+RY;     // 「先手の竜」
  public static final int GFU=GOTE +FU;     // 「後手の歩」＝「歩」＋「後手」
  public static final int GKY=GOTE +KY;     // 「後手の香」
  public static final int GKE=GOTE +KE;     // 「後手の桂」
  public static final int GGI=GOTE +GI;     // 「後手の銀」
  public static final int GKI=GOTE +KI;     // 「後手の金」
  public static final int GKA=GOTE +KA;     // 「後手の角」
  public static final int GHI=GOTE +HI;     // 「後手の飛」
  public static final int GOU=GOTE +OU;     // 「後手の玉」
  public static final int GTO=GOTE +TO;     // 「後手のと金」
  public static final int GNY=GOTE +NY;     // 「後手の成香」
  public static final int GNK=GOTE +NK;     // 「後手の成桂」
  public static final int GNG=GOTE +NG;     // 「後手の成銀」
  public static final int GUM=GOTE +UM;     // 「後手の馬」
  public static final int GRY=GOTE +RY;     // 「後手の竜」
  public static final int WALL=64;          // 盤の外を表すための定数
  // 先手の駒かどうかの判定
  static public boolean isSente(int koma) {
    return (koma & SENTE)!=0;
  }
  // 後手の駒かどうかの判定
  static public boolean isGote(int koma) {
    return (koma & GOTE)!=0;
  }
  // 手番から見て自分の駒かどうか判定
  static public boolean isSelf(int teban,int koma) {
    if (teban==SENTE) {
      return isSente(koma);
    } else {
      return isGote(koma);
    }
  }
  // 手番から見て相手の駒かどうか判定
  static public boolean isEnemy(int teban,int koma) {
    if (teban==SENTE) {
      return isGote(koma);
    } else {
      return isSente(koma);
    }
  }
  // 駒の種類の取得
  static public int getKomashu(int koma) {
    // 先手後手のフラグをビット演算でなくせば良い。
    return koma & 0x0f;
  }
  // 駒の文字列化用の文字列
  static public final String komaString[]={
    "",
    "歩",
    "香",
    "桂",
    "銀",
    "金",
    "角",
    "飛",
    "王",
    "と",
    "杏",
    "圭",
    "全",
    "",
    "馬",
    "竜"
  };
  // 駒の文字列化…盤面の表示用
  static public String toBanString(int koma) {
    if ( koma==EMPTY ) {
      return "   ";
    } else if ( (koma & SENTE) !=0 ) {
      // 先手の駒には、" "を頭に追加
      return " "+komaString[getKomashu(koma)];
    } else {
      // 後手の駒には、"v"を頭に追加
      return "v"+komaString[getKomashu(koma)];
    }
  }
  // 駒の文字列化…持ち駒、手などの表示用
  static public String toString(int koma) {
    return komaString[getKomashu(koma)];
  }
  // 駒が成れるかどうかを表す
  public static final boolean canPromote[]={
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false, true, true, true, true,false, true, true,// 空、先手の歩香桂銀金角飛
     false,false,false,false,false,false,false,false,// 先手の王、と杏圭全馬竜
     false, true, true, true, true,false, true, true,// 空、後手の歩香桂銀金角飛
     false,false,false,false,false,false,false,false // 後手の王、と杏圭全馬竜
  };
  static public boolean canPromote(int koma) {
    return canPromote[koma];
  }
}
interface KomaMoves {
  // 通常の８方向の定義(盤面上の動き)
  // 
  //  5   6  7
  //     ↑
  //  3←駒→4
  //     ↓
  //  2   1  0
  //
  // 桂馬飛びの方向の定義(盤面上の動き)
  //
  //   8    9
  //
  //     桂
  //
  //   11  10
  // 方向の定義に沿った、「段」の移動の定義
  public static final int diffDan[]={
     1, 1, 1, 0, 0,-1,-1,-1,-2,-2, 2, 2
  };
  // 方向の定義に沿った、「筋」の移動の定義
  public static final int diffSuji[]={
    -1, 0, 1, 1,-1, 1, 0,-1, 1,-1,-1, 1
  };
  // 方向の定義に沿った、「移動」の定義
  public static final int diff[]={
    diffSuji[0]*16+diffDan[0],
    diffSuji[1]*16+diffDan[1],
    diffSuji[2]*16+diffDan[2],
    diffSuji[3]*16+diffDan[3],
    diffSuji[4]*16+diffDan[4],
    diffSuji[5]*16+diffDan[5],
    diffSuji[6]*16+diffDan[6],
    diffSuji[7]*16+diffDan[7],
    diffSuji[8]*16+diffDan[8],
    diffSuji[9]*16+diffDan[9],
    diffSuji[10]*16+diffDan[10],
    diffSuji[11]*16+diffDan[11]
  };
  // ある方向にある駒が動けるかどうかを表すテーブル。
  // 添え字の１つめが方向で、２つめが駒の種類である。
  // 香車や飛車、角などの一直線に動く動きについては、後述のcanJumpで表し、
  // このテーブルではfalseとしておく。
  public static final boolean canMove[][]={
    // 方向 0 斜め左下への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false, true,false,false,false,// 空、先手の歩香桂銀金角飛
      true,false,false,false,false,false,false, true,// 先手の王、と杏圭全馬竜
     false,false,false,false, true, true,false,false,// 空、後手の歩香桂銀金角飛
      true, true, true, true, true, true,false, true // 後手の王、と杏圭全馬竜
    },
    // 方向 1 真下への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false, true,false,false,// 空、先手の歩香桂銀金角飛
      true, true, true, true, true,false, true,false,// 先手の王、と杏圭全馬竜
     false, true,false,false, true, true,false,false,// 空、後手の歩香桂銀金角飛
      true, true, true, true, true,false, true,false // 後手の王、と杏圭全馬竜
    },
    // 方向 2 斜め右下への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false, true,false,false,false,// 空、先手の歩香桂銀金角飛
      true,false,false,false,false,false,false, true,// 先手の王、と杏圭全馬竜
     false,false,false,false, true, true,false,false,// 空、後手の歩香桂銀金角飛
      true, true, true, true, true, true,false, true // 後手の王、と杏圭全馬竜
    },
    // 方向 3 左への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false, true,false,false,// 空、先手の歩香桂銀金角飛
      true, true, true, true, true,false, true,false,// 先手の王、と杏圭全馬竜
     false,false,false,false,false, true,false,false,// 空、後手の歩香桂銀金角飛
      true, true, true, true, true,false, true,false // 後手の王、と杏圭全馬竜
    },
    // 方向 4 右への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false, true,false,false,// 空、先手の歩香桂銀金角飛
      true, true, true, true, true,false, true,false,// 先手の王、と杏圭全馬竜
     false,false,false,false,false, true,false,false,// 空、後手の歩香桂銀金角飛
      true, true, true, true, true,false, true,false // 後手の王、と杏圭全馬竜
    },
    // 方向 5 斜め左上への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false, true, true,false,false,// 空、先手の歩香桂銀金角飛
      true, true, true, true, true,false,false, true,// 先手の王、と杏圭全馬竜
     false,false,false,false, true,false,false,false,// 空、後手の歩香桂銀金角飛
      true,false,false,false,false,false,false, true // 後手の王、と杏圭全馬竜
    },
    // 方向 6 真上への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false, true,false,false, true, true,false,false,// 空、先手の歩香桂銀金角飛
      true, true, true, true, true,false, true,false,// 先手の王、と杏圭全馬竜
     false,false,false,false,false, true,false,false,// 空、後手の歩香桂銀金角飛
      true, true, true, true, true,false, true,false // 後手の王、と杏圭全馬竜
     },
     // 方向 7 斜め右上への動き
     {
      false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
      false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
      false,false,false,false, true, true,false,false,// 空、先手の歩香桂銀金角飛
       true, true, true, true, true,false,false, true,// 先手の王、と杏圭全馬竜
      false,false,false,false, true,false,false,false,// 空、後手の歩香桂銀金角飛
       true,false,false,false,false,false,false, true // 後手の王、と杏圭全馬竜
     },
     // 方向 8 先手の桂馬飛び
     {
      false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
      false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
      false,false,false, true,false,false,false,false,// 空、先手の歩香桂銀金角飛
      false,false,false,false,false,false,false,false,// 先手の王、と杏圭全馬竜
      false,false,false,false,false,false,false,false,// 空、後手の歩香桂銀金角飛
      false,false,false,false,false,false,false,false // 後手の王、と杏圭全馬竜
     },
     // 方向 9 先手の桂馬飛び
     {
      false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
      false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
      false,false,false, true,false,false,false,false,// 空、先手の歩香桂銀金角飛
      false,false,false,false,false,false,false,false,// 先手の王、と杏圭全馬竜
      false,false,false,false,false,false,false,false,// 空、後手の歩香桂銀金角飛
      false,false,false,false,false,false,false,false // 後手の王、と杏圭全馬竜
     },
     // 方向10 後手の桂馬飛び
     {
      false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
      false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
      false,false,false,false,false,false,false,false,// 空、先手の歩香桂銀金角飛
      false,false,false,false,false,false,false,false,// 先手の王、と杏圭全馬竜
      false,false,false, true,false,false,false,false,// 空、後手の歩香桂銀金角飛
      false,false,false,false,false,false,false,false // 後手の王、と杏圭全馬竜
     },
     // 方向11 後手の桂馬飛び
     {
      false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
      false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
      false,false,false,false,false,false,false,false,// 空、先手の歩香桂銀金角飛
      false,false,false,false,false,false,false,false,// 先手の王、と杏圭全馬竜
      false,false,false, true,false,false,false,false,// 空、後手の歩香桂銀金角飛
      false,false,false,false,false,false,false,false // 後手の王、と杏圭全馬竜
     }
  };
  // ある方向にある駒が飛べるかどうかを表すテーブル。
  // 添え字の１つめが方向で、２つめが駒の種類である。
  // 香車や飛車、角、竜、馬の一直線に動く動きについては、こちらで表す。
  static final public boolean canJump[][]={
    // 方向 0 斜め左下への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false, true,false,// 空、先手の歩香桂銀金角飛
     false,false,false,false,false,false, true,false,// 先手の王、と杏圭全馬竜
     false,false,false,false,false,false, true,false,// 空、後手の歩香桂銀金角飛
     false,false,false,false,false,false, true,false // 後手の王、と杏圭全馬竜
    },
    // 方向 1 真下への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false, true,// 空、先手の歩香桂銀金角飛
     false,false,false,false,false,false,false, true,// 先手の王、と杏圭全馬竜
     false,false, true,false,false,false,false, true,// 空、後手の歩香桂銀金角飛
     false,false,false,false,false,false,false, true // 後手の王、と杏圭全馬竜
    },
    // 方向 2 斜め右下への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false, true,false,// 空、先手の歩香桂銀金角飛
     false,false,false,false,false,false, true,false,// 先手の王、と杏圭全馬竜
     false,false,false,false,false,false, true,false,// 空、後手の歩香桂銀金角飛
     false,false,false,false,false,false, true,false // 後手の王、と杏圭全馬竜
    },
    // 方向 3 左への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false, true,// 空、先手の歩香桂銀金角飛
     false,false,false,false,false,false,false, true,// 先手の王、と杏圭全馬竜
     false,false,false,false,false,false,false, true,// 空、後手の歩香桂銀金角飛
     false,false,false,false,false,false,false, true // 後手の王、と杏圭全馬竜
    },
    // 方向 4 右への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false, true,// 空、先手の歩香桂銀金角飛
     false,false,false,false,false,false,false, true,// 先手の王、と杏圭全馬竜
     false,false,false,false,false,false,false, true,// 空、後手の歩香桂銀金角飛
     false,false,false,false,false,false,false, true // 後手の王、と杏圭全馬竜
    },
    // 方向 5 斜め左上への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false, true,false,// 空、先手の歩香桂銀金角飛
     false,false,false,false,false,false, true,false,// 先手の王、と杏圭全馬竜
     false,false,false,false,false,false, true,false,// 空、後手の歩香桂銀金角飛
     false,false,false,false,false,false, true,false // 後手の王、と杏圭全馬竜
    },
    // 方向 6 真上への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false, true,false,false,false,false, true,// 空、先手の歩香桂銀金角飛
     false,false,false,false,false,false,false, true,// 先手の王、と杏圭全馬竜
     false,false,false,false,false,false,false, true,// 空、後手の歩香桂銀金角飛
     false,false,false,false,false,false,false, true // 後手の王、と杏圭全馬竜
    },
    // 方向 7 斜め右上への動き
    {
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false,false,false,// 先手でも後手でもない駒
     false,false,false,false,false,false, true,false,// 空、先手の歩香桂銀金角飛
     false,false,false,false,false,false, true,false,// 先手の王、と杏圭全馬竜
     false,false,false,false,false,false, true,false,// 空、後手の歩香桂銀金角飛
     false,false,false,false,false,false, true,false // 後手の王、と杏圭全馬竜
    }
    // 桂馬の方向に飛ぶ駒はないので、以下は省略。
  };
}
class Kyokumen implements Constants,Cloneable {
  // 盤面
  int ban[];
  // 持ち駒
  int hand[];
  // 手番
  int teban=SENTE;
  // 現在の先手からみた評価値
  int eval=0;
  // 先手玉の位置…盤外の、利きの届かないところ、(-2,-2)=-2*16-2=-34に設定。
  int kingS=-34;
  // 後手玉の位置…盤外の、利きの届かないところ、(-2,-2)=-2*16-2=-34に設定。
  int kingG=-34;
  // 使った時間
  int spentTime[]=new int[2];
  // 手数
  int tesu=0;
  public Kyokumen() {
    ban=new int[16*11];
    hand=new int[Koma.GHI+1];
    // 盤面全体を「カベ」で一旦埋める
    for(int i=0;i<16*11;i++) {
      ban[i]=Koma.WALL;
    }
    // 盤面にあたる場所を空白に設定する
    for(int suji=1;suji<=9;suji++) {
      for(int dan=1;dan<=9;dan++) {
        ban[(suji<<4)+dan]=Koma.EMPTY;
      }
    }
  }
  // 局面のコピーを行う
  public Object clone() {
    Kyokumen k=new Kyokumen();
    // 盤面のコピー
    for(int i=0;i<16*11;i++) {
      k.ban[i]=ban[i];
    }
    // 持ち駒のコピー
    for(int i=Koma.SFU;i<=Koma.GHI;i++) {
      k.hand[i]=hand[i];
    }
    // 手番のコピー
    k.teban=teban;
    // 評価値のコピー
    k.eval=eval;
    // 玉の位置のコピー
    k.kingS=kingS;
    k.kingG=kingG;
    return k;
  }
  // 局面が同一かどうか
  public boolean equals(Object o) {
    Kyokumen k=(Kyokumen)o;
    if (k==null) return false;
    return equals(k);
  }
  // 局面が同一かどうか
  public boolean equals(Kyokumen k) {
    // 手番の比較
    if (teban!=k.teban) {
      return false;
    }
    // 盤面の比較
    // 各マスについて…
    for(int suji=0x10;suji<=0x90;suji+=0x10) {
      for(int dan=1;dan<=9;dan++) {
        // 盤面上の筋と段にある駒が、比較対象の盤面上の同じ位置にある駒と
        // 同じかどうか比較する。
        if (ban[suji+dan]!=k.ban[suji+dan]) {
          // 違っていたら、falseを返す。
          return false;
        }
      }
    }
    // 持ち駒の比較
    // 持ち駒の枚数を比較する。
    for(int i=Koma.SFU;i<=Koma.GHI;i++) {
      if (hand[i]!=k.hand[i]) {
        // 違っていたら、falseを返す。
        return false;
      }
    }
    // 完全に一致した。
    return true;
  }
  // ある位置にある駒を取得する
  public int get(int p) {
    // 盤外なら、「盤外＝壁」を返す
    if (p<0 || 16*11<p) {
      return Koma.WALL;
    }
    return ban[p];
  }
  // ある位置にある駒を置く。
  public void put(int p,int koma) {
    ban[p]=koma;
  }
  // 与えられた手で一手進めてみる。
  public void move(Te te) {
    // 盤面からあった駒がなくなる
    BanHash^=HashSeed[get(te.to)][te.to];
    // 駒の行き先に駒があったなら…
    if (get(te.to)!=Koma.EMPTY) {
      // 盤面からその駒がなくなった分、評価値を減じる。
      eval-=komaValue[get(te.to)];
      // 持ち駒にする
      if (Koma.isSente(get(te.to))) {
        // 取った駒が先手の駒なら後手の持ち駒に。
        int koma=get(te.to);
        // 成りなどのフラグ、先手・後手の駒のフラグをクリア。
        koma=koma & 0x07;
        // 後手の駒としてのフラグをセット
        koma=koma | GOTE;
        // 持ち駒に追加。
        hand[koma]++;
        // 持ち駒に駒が追加される
        HandHash^=HandHashSeed[koma][hand[koma]];
        eval+=komaValue[koma];
      } else {
        // 取った駒が後手の駒なら先手の持ち駒に。
        int koma=get(te.to);
        // 成りなどのフラグ、先手・後手の駒のフラグをクリア。
        koma=koma & 0x07;
        // 先手の駒としてのフラグをセット
        koma=koma | SENTE;
        // 持ち駒に追加。
        hand[koma]++;
        // 持ち駒に駒が追加される
        HandHash^=HandHashSeed[koma][hand[koma]];
        eval+=komaValue[koma];
      }
    }
    if (te.from==0) {
      // 持ち駒を打った
      // 持ち駒を一枚減らす。
      HandHash^=HandHashSeed[te.koma][hand[te.koma]];
      hand[te.koma]--;
    } else {
      // 盤上の駒を進めた→元の位置は、EMPTYに。
      put(te.from,Koma.EMPTY);
      BanHash^=HashSeed[te.koma][te.from];
      BanHash^=HashSeed[Koma.EMPTY][te.from];
    }
    // 駒を移動先に進める。
    int koma=te.koma;
    if (te.promote) {
      // 「成り」の処理
      // 成る前の駒の価値を減じる
      eval-=komaValue[koma];
      koma=koma|Koma.PROMOTE;
      // 成った後の駒の価値を加える
      eval+=komaValue[koma];
    }
    put(te.to,koma);
    BanHash^=HashSeed[koma][te.to];
    if (te.koma==Koma.SOU) {
      kingS=te.to;
    } else if (te.koma==Koma.GOU) {
      kingG=te.to;
    }
    HashVal=BanHash^HandHash;
  }
  // 与えられた手で一手戻す。
  public void back(Te te) {
    // 盤面からあった駒がなくなる
    BanHash^=HashSeed[get(te.to)][te.to];
    // 取った駒を盤に戻す
    put(te.to,te.capture);
    // 盤面に駒を戻す
    BanHash^=HashSeed[te.capture][te.to];
    // 評価点も戻す
    eval+=komaValue[te.capture];
    // 取った駒がある時には…
    if (te.capture!=Koma.EMPTY) {
      // 持ち駒に入っているはずなので、減らす。
      if (Koma.isSente(te.capture)) {
        // 取った駒が先手の駒なら後手の持ち駒に。
        int koma=te.capture;
        // 成りなどのフラグ、先手・後手の駒のフラグをクリア。
        koma=koma & 0x07;
        // 後手の駒としてのフラグをセット
        koma=koma | GOTE;
        // 持ち駒から減らす
        HandHash^=HandHashSeed[koma][hand[koma]];
        hand[koma]--;
        eval-=komaValue[koma];
      } else {
        // 取った駒が後手の駒なら先手の持ち駒に。
        int koma=te.capture;
        // 成りなどのフラグ、先手・後手の駒のフラグをクリア。
        koma=koma & 0x07;
        // 先手の駒としてのフラグをセット
        koma=koma | SENTE;
        // 持ち駒から減らす。
        HandHash^=HandHashSeed[koma][hand[koma]];
        hand[koma]--;
        eval-=komaValue[koma];
      }
    }
    if (te.from==0) {
      // 駒打ちだったので、持ち駒に戻す
      hand[te.koma]++;
      HandHash^=HandHashSeed[te.koma][hand[te.koma]];
      BanHash^=HashSeed[Koma.EMPTY][te.from];
    } else {
      // 動かした駒を元の位置に戻す。
      put(te.from,te.koma);
      BanHash^=HashSeed[Koma.EMPTY][te.from];
      BanHash^=HashSeed[te.koma][te.from];
      if (te.promote) {
        // 成っていたので、その分の点数を計算しなおす。
        // 成った後の駒の価値を減じる
        int koma=te.koma|Koma.PROMOTE;
        eval-=komaValue[koma];
        // 成る前の駒の価値を加える
        eval+=komaValue[te.koma];
      }
    }
    if (te.koma==Koma.SOU) {
      kingS=te.from;
    } else if (te.koma==Koma.GOU) {
      kingG=te.from;
    }
    HashVal=BanHash^HandHash;
  }
  // kingS,kingGを初期化する
  void initKingPos() {
    // 先手と後手の玉の位置…盤外で、利きの届かない位置(-2,-2)にあたる
    // 位置で初期化しておく。
    kingS=-34;
    kingG=-34;
    // 筋、段でループ
    for(int suji=0x10;suji<=0x90;suji+=0x10) {
      for(int dan=1;dan<=9;dan++) {
        if (ban[suji+dan]==Koma.SOU) {
          // 見つかった
          kingS=suji+dan;
        }
        if (ban[suji+dan]==Koma.GOU) {
          // 見つかった
          kingG=suji+dan;
        }
      }
    }
  }
  // 玉の位置を返す
  public int searchGyoku(int teban) {
    if (teban==SENTE) {
      return kingS;
    } else {
      return kingG;
    }
  }
  // 局面を評価するための、駒の価値。
  // 先手の駒はプラス点、後手の駒はマイナス点にする。
  static final int komaValue[]={
    0,    0,    0,    0,    0,    0,    0,    0, // 何もない場所及び
    0,    0,    0,    0,    0,    0,    0,    0, // 先手でも後手でもない駒
    0,  100,  500,  600,  800,  900, 1300, 1500, // 何もない場所、先手の歩〜飛車
    10000, 1100,  800,  800,  900,    0, 1500, 1700, // 先手玉、及びと〜竜
    0, -100, -500, -600,-800,-900,-1300,-1500, // 何もない場所、後手の歩〜飛車
    -10000,-1100,-800,-800,-900,    0,-1500,-1700  // 後手玉、及びと〜竜
  };
  // 初期化した際に、局面を評価する関数
  void initEval() {
    eval=0;
    // まず、盤面の駒から。
    for(int suji=0x10;suji<=0x90;suji+=0x10) {
      for(int dan=1;dan<=9;dan++) {
        eval+=komaValue[ban[suji+dan]];
      }
    }
    // 次に、持ち駒
    for(int i=Koma.SFU;i<=Koma.SHI;i++) {
      eval+=komaValue[i]*hand[i];
    }
    for(int i=Koma.GFU;i<=Koma.GHI;i++) {
      eval+=komaValue[i]*hand[i];
    }
  }
  public void initAll() {
    initEval();
    initKingPos();
    CalcHash();
  }
  // 局面を評価する関数。
  public int evaluate() {
    return eval;
  }
  // CSA形式の棋譜ファイル文字列
  static final String csaKomaTbl[] = {
    "   ","FU","KY","KE","GI","KI","KA","HI",
    "OU","TO","NY","NK ","NG","","UM","RY",
    ""  ,"+FU","+KY","+KE","+GI","+KI","+KA","+HI",
    "+OU","+TO","+NY","+NK","+NG",""   ,"+UM","+RY",
    ""  ,"-FU","-KY","-KE","-GI","-KI","-KA","-HI",
    "-OU","-TO","-NY","-NK","-NG",""   ,"-UM","-RY"
  };
  // CSA形式の棋譜ファイルから、局面を読み込む
  public void ReadCsaKifu(String[] csaKifu) {
    // 駒箱に入っている残りの駒。残りを全て持ち駒にする際などに使用する。
    int restKoma[]=new int[Koma.HI+1];
    // 駒箱に入っている駒＝その種類の駒の枚数
    restKoma[Koma.FU]=18;
    restKoma[Koma.KY]=4;
    restKoma[Koma.KE]=4;
    restKoma[Koma.GI]=4;
    restKoma[Koma.KI]=4;
    restKoma[Koma.KA]=2;
    restKoma[Koma.HI]=2;
    // 盤面を空に初期化
    for(int suji=0x10;suji<=0x90;suji+=0x10) {
      for(int dan=1;dan<=9;dan++) {
        ban[suji+dan]=Koma.EMPTY;
      }
    }
    // 文字列から読み込み
    for(int i=0;i<csaKifu.length;i++) {
      String line=csaKifu[i];
      System.out.println(""+i+" :"+line);
      if (line.startsWith("P+")) {
        if (line.equals("P+00AL")) {
          // 残りの駒は全部先手の持ち駒
          for(int koma=Koma.FU;koma<=Koma.HI;koma++) {
            hand[SENTE|koma]=restKoma[koma];
          }
        } else {
          // 先手の持ち駒
          for(int j=0;j<=line.length()-6;j+=4) {
            int koma=0;
            String komaStr=line.substring(j+2+2,j+2+4);
            for(int k=Koma.FU;k<=Koma.HI;k++) {
              if(komaStr.equals(csaKomaTbl[k])) {
                koma=k;
                break;
              }
            }
            hand[SENTE|koma]++;
          }
        }
      } else if (line.startsWith("P-")) {
        if (line.equals("P-00AL")) {
          // 残りの駒は全部後手の持ち駒
          for(int koma=Koma.FU;koma<=Koma.HI;koma++) {
            hand[GOTE|koma]=restKoma[koma];
          }
        } else {
          // 後手の持ち駒
          for(int j=0;j<line.length();j+=4) {
            int koma=0;
            for(int k=Koma.FU;k<=Koma.HI;k++) {
              if(line.substring(j+2,j+4).equals(csaKomaTbl[k])) {
                koma=k;
                break;
              }
            }
            hand[GOTE|koma]++;
          }
        }
      } else if (line.startsWith("P")) {
        // 盤面の表現
        // P1〜P9まで。
        String danStr=line.substring(1,2);
        int dan=0;
        try {
          dan=Integer.parseInt(danStr);
        } catch(Exception e) {
          // …握りつぶすことにしておく。
        }
        String komaStr;
        for(int suji=1;suji<=9;suji++) {
          // ややこしいが、左側が９筋、右側が１筋…
          // 文字列の頭の方が９筋で、後ろの方が１筋。
          // そのため、読み込みの時に逆さに読み込む。
          komaStr=line.substring(2+(9-suji)*3,2+(9-suji)*3+3);
          int koma=Koma.EMPTY;
          for(int k=Koma.EMPTY;k<=Koma.GRY;k++) {
            if (komaStr.equals(csaKomaTbl[k])) {
              koma=k;
              // 成のフラグを取って、残りの駒から
              // その種類の駒を一枚ひいておく。
              restKoma[(Koma.getKomashu(koma) & ~Koma.PROMOTE)]--;
              break;
            }
          }
          ban[(suji<<4)+dan]=koma;
        }
      } else if (line.equals("-")) {
        teban=GOTE;
      } else if (line.equals("+")) {
        teban=SENTE;
      }
    }
    initAll();
  }
  // 局面を表示用に文字列化
  public String toString() {
    String s="";
    // 後手持ち駒表示
    s+="後手持ち駒：";
    for(int i=Koma.GFU;i<=Koma.GHI;i++) {
      if (hand[i]==1) {
        s+=Koma.toString(i);
      } else if (hand[i]>1) {
        s+=Koma.toString(i)+hand[i];
      }
    }
    s+="\n";
    // 盤面表示
    s+=" ９  ８  ７  ６  ５  ４  ３  ２  １\n";
    s+="+---+---+---+---+---+---+---+---+---+\n";
    for(int dan=1;dan<=9;dan++) {
      for(int suji=9;suji>=1;suji--) {
        s+="|";
        s+=Koma.toBanString(ban[(suji<<4)+dan]);
      }
      s+="|";
      s+=danStr[dan];
      s+="\n";
      s+="+---+---+---+---+---+---+---+---+---+\n";
    }
    // 先手持ち駒表示
    s+="先手持ち駒：";
    for(int i=Koma.SFU;i<=Koma.SHI;i++) {
      if (hand[i]==1) {
        s+=Koma.toString(i);
      } else if (hand[i]>1) {
        s+=Koma.toString(i)+hand[i];
      }
    }
    s+="\n";
    return s;
  }
  // 平手の初期盤面を与える
  static final int ShokiBanmen[][]={
    {Koma.GKY,Koma.GKE,Koma.GGI,Koma.GKI,Koma.GOU,Koma.GKI,Koma.GGI,Koma.GKE,Koma.GKY},
    {Koma.EMP,Koma.GHI,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.GKA,Koma.EMP},
    {Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU},
    {Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP},
    {Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP},
    {Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP},
    {Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU},
    {Koma.EMP,Koma.SKA,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.SHI,Koma.EMP},
    {Koma.SKY,Koma.SKE,Koma.SGI,Koma.SKI,Koma.SOU,Koma.SKI,Koma.SGI,Koma.SKE,Koma.SKY},
  };
  void initHirate() {
    teban=SENTE;
    for(int dan=1;dan<=9;dan++) {
      for(int suji=9;suji>=1;suji--) {
        ban[(suji<<4)+dan]=ShokiBanmen[dan-1][9-suji];
      }
    }
    for(int koma=Koma.SFU;koma<=Koma.GHI;koma++) {
      hand[koma]=0;
    }
    // 諸々の初期化を行う。
    initAll();
  }
  // ハッシュ値関連
  // 盤面のハッシュ値の種
  static private int HashSeed[][];
  // 「手」のハッシュ値の種
  static private int HandHashSeed[][];
  public int HashVal;
  public int BanHash;
  public int HandHash;
  static long seed=0;
  // bits数の乱数を得る。
  static protected int rand(int bits) {
    seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
    return (int)(seed >>> (48 - bits));
  }
  // ハッシュの種の初期化
  static {
    seed=0;
    HashSeed=new int [Koma.GRY+1][16*11];
    HandHashSeed=new int[Koma.GHI+1][20];
    for(int i=0;i<=Koma.GRY;i++) {
      for(int j=0;j<16*11;j++) {
        HashSeed[i][j]=rand(30);
      }
    }
    for(int i=0;i<=Koma.GHI;i++) {
      for(int j=0;j<20;j++) {
        HandHashSeed[i][j]=rand(30);
      }
    }
  }
  void CalcHash() {
    HandHash=0;
    BanHash=0;
    int i,j;
    for(i=0;i<=Koma.GHI;i++) {
      for(j=0;j<=hand[i];j++) {
        HandHash^=HandHashSeed[i][j];
      }
    }
    for(i=1;i<=9;i++) {
      for(j=1;j<=9;j++) {
        BanHash^=HashSeed[ban[i*16+j]][i*16+j];
      }
    }
    HashVal=HandHash^BanHash;
    //System.out.println(Integer.toHexString(HashVal));
  }
}
// 駒組みを評価する局面クラス
class KyokumenKomagumi extends Kyokumen {
  // 各駒が何段目にいるか、でのボーナス
  static final int DanValue[][]={
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //空
      {0,0,0,0,0,0,0,0,0,0},
  //歩
      { 0,  0,15,15,15,3,1, 0, 0, 0},
  //香
      { 0, 1,2,3,4,5,6,7,8,9},
  //桂
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //銀
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //金
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //角
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //飛
      { 0,10,10,10, 0, 0, 0,  -5, 0, 0},
  //王
      { 0,1200,1200,900,600,300,-10,0,0,0},
  //と
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //杏
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //圭
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //全
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //金
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //馬
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //龍
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //空
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //歩
      { 0, 0, 0, 0, -1, -3,-15,-15,-15, 0},
  //香
      { 0,-9,-8,-7, -6, -5, -4, -3, -2,-1},
  //桂
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //銀
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //金
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //角
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //飛
      { 0, 0, 0, 5, 0, 0, 0,-10,-10,-10},
  //王
      { 0, 0, 0, 0,10,-300,-600,-900,-1200,-1200},
  //と
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //杏
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //圭
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //全
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //金
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //馬
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
  //龍
      { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
  };
  // 戦形の定義
  static final int IvsFURI=0;		// 居飛車対振り飛車
  static final int IvsNAKA=1;		// 居飛車対中飛車
  static final int FURIvsFURI=2;	// 相振り飛車
  static final int FURIvsI=3;		// 振り飛車対居飛車
  static final int NAKAvsI=4;		// 中飛車対居飛車
  static final int KAKUGAWARI=5;	// 角換り
  static final int AIGAKARI=6;		// 相掛かり（または居飛車の対抗系）
  static final int HUMEI=7;		// 戦形不明
  // 各戦形別に、自分の駒に与える、位置によるボーナス点
  // まず、銀
  static final int JosekiKomagumiSGI[][][]={
    { // IvsFURI 舟囲い、美濃、銀冠
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10, -7,-10,-10,-10,-10,-10,  7,-10},
      {-10,  7, -8, -7, 10,-10, 10,  6,-10},
      {-10, -2, -6, -5,-10,  6,-10,-10,-10},
      {-10, -7,  0,-10,-10,-10,-10,-10,-10}
    },{	// IvsNAKA舟囲い
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10, -7,-10,-10, -7,-10,-10,  7,-10},
      {-10, -5, -8, -7, 10,-10, 10,  6,-10},
      {-10, -2, -3,  0,-10,  6,-10,-10,-10},
      {-10, -7, -5,-10,-10,-10,-10,-10,-10}
    },{ // FURIvsFURI矢倉（逆）、美濃、銀冠
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10, -7, -7,-10},
      {-10,-10,-10,-10,-10,  5, 10, 10,-10},
      {-10,-10,-10,-10,-10,-10,  0,-10,-10},
      {-10,-10,-10,-10,-10,-10, -5,-10,-10}
    },{ // FURIvsI 美濃囲い、銀冠
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10, -3, -7,-10,-10,-10,-10,-10},
      {-10, -7,  4,  6,-10,-10,-10,  6,-10},
      {-10,  2,  3,  3,-10,-10,  4,-10,-10},
      {-10,-10,-10,  0,-10,-10,  0,-10,-10}
    },{ // NAKAvsI 中飛車
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,  8,  5,  8,-10,-10,-10},
      {-10,-10,  4,  4,  3,  4,  4,-10,-10},
      {-10,-10,  0,-10,-10,-10,  0,-10,-10}
    },{ // KAKUGAWARI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,  7,  5, -3,-10,-10},
      {-10,  8, 10,  7,  4,  0, -4,-10,-10},
      {-10,  0,-8,  -4,-10,-10, -5,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10}
    },{ // AIGAKARI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,  0,-10,-10,-10,-10,-10,-10},
      {-10, -5,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10}
    },{ // HUMEI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,  5,-10,-10},
      {-10,-10,-10,-10,-10,-10, -4,  0,-10},
      {-10,-10,  0,-10,-10,-10, -4, -3,-10},
      {-10, -5,-10, -5,-10,-10, -5,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10}
    }
  };
  // 金
  static final int JosekiKomagumiSKI[][][]={
    { // IvsFURI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,  1,  2,-10,-10,-10,-10},
      {-10,-10,-10,  0,-10, -4,-10,-10,-10}
    },{	// IvsNAKA
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,  1,  2,-10,-10,-10,-10},
      {-10,-10,-10,  0,-10, -4,-10,-10,-10}
    },{ // FURIvsFURI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,  7, -3,-10,-10},
      {-10,-10,-10,-10,  5,  3,  6,-10,-10},
      {-10,-10,-10,-10,-10,  5,  4,-10,-10}
    },{ // FURIvsI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,  5,  1,-10,-10},
      {-10,-10,-10,-10,  4,  3,  7, -3,-10},
      {-10,-10,-10,  0,  1,  5,  2, -7,-10}
    },{ // NAKAvsI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10, -7, -4, -4,-10, -4, -4, -7,-10},
      {-10, -5, 10,  6,-10,  8, 10, -5,-10},
      {-10, -7, -6, -3, -6, -3, -6, -7,-10}
    },{ // KAKUGAWARI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,  6, -4, -4, -4, -8,-10},
      {-10,-10, 10,-10,  3,  0,  0, -7,-10},
      {-10,-10,-10,  0,-10,  0, -5, -7,-10}
    },{ // AIGAKARI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,  6,-10,-10,-10,-10,-10},
      {-10,-10, 10,-10,  3,-10,-10,-10,-10},
      {-10,-10,-10,  0,-10,  0,-10,-10,-10}
    },{ // HUMEI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,  3,-10,  5,-10,-10,-10,-10},
      {-10,-10,-10,  0,-10,  0,-10,-10,-10}
    }
  };
  // 玉
  static final int JosekiKomagumiSOU[][][]={
    {
      // IvsFURI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {- 7,  9,-10,-10,-10,-10,-10,-10,-10},
      {  5,  7,  8,  4,-10,-10,-10,-10,-10},
      { 10,  5,  3,-10,-10,-10,-10,-10,-10}
    },{	// IvsNAKA
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {- 7,  9,-10,-10,-10,-10,-10,-10,-10},
      {  5,  7,  8,  4,-10,-10,-10,-10,-10},
      { 10,  5,  3,-10,-10,-10,-10,-10,-10}
    },{ // FURIvsFURI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,  4,  6, 10,  6},
      {-10,-10,-10,-10,-10,  4,  6,  5, 10}
    },{ // FURIvsI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,  4,  6, 10,  6},
      {-10,-10,-10,-10,-10,  4,  6,  5, 10}
    },{ // NAKAvsI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,  4,  6, 10,  6},
      {-10,-10,-10,-10,-10,  4,  6,  5, 10}
    },{ // KAKUGAWARI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {- 3, -4, -3,-10,-10,-10,-10,-10,-10},
      {  6,  8, -2,  0, -3,-10,-10,-10,-10},
      { 10,  6, -4,- 6,- 7,-10,-10,-10,-10}
    },{ // AIGAKARI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {- 3, -4, -3,-10,-10,-10,-10,-10,-10},
      {  6,  8,  0,- 4,-10,-10,-10,-10,-10},
      { 10,  6, -4,- 6,- 7,-10,-10,-10,-10}
    },{ // HUMEI
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {-10,-10,-10,-10,-10,-10,-10,-10,-10},
      {- 3, -4, -3,-10,-10,-10,-10,-10,-10},
      {  6,  8,  0,- 4,-10,-10,-10,-10,-10},
      { 10,  6, -4,- 6,- 7,-10,-10,-10,-10}
    }
  };
  // 各駒の駒組みによるボーナス点のテーブル
  static int JosekiKomagumi[][][]=new int[9][Koma.GRY+1][16*11];
  static int komagumiValue[][]=new int[Koma.GRY+1][16*11];
  public KyokumenKomagumi() {
  }
  public KyokumenKomagumi(Kyokumen k) {
    // 盤面のコピー
    for(int i=0;i<16*11;i++) {
      ban[i]=k.ban[i];
    }
    // 持ち駒のコピー
    for(int i=Koma.SFU;i<=Koma.GHI;i++) {
      hand[i]=k.hand[i];
    }
    // 手番のコピー
    teban=k.teban;
    // 評価値のコピー
    eval=k.eval;
    // 玉の位置のコピー
    kingS=k.kingS;
    kingG=k.kingG;
    // 必要な駒組みテーブルなどを初期化
    initTbl();
    senkeiInit();
    initShuubando();
    initBonus();
  }
  public void initTbl() {
    int suji,dan,koma;
    for(suji=0x10;suji<=0x90;suji+=0x10) {
      for(dan=1;dan<=9;dan++) {
        for(koma=Koma.SFU;koma<=Koma.GRY;koma++) {
          komagumiValue[koma][suji+dan]=0;
          JosekiKomagumi[0][koma][suji+dan]=DanValue[koma][dan];
        }
      }
    }
  }
  public void senkeiInit() {
    int SHI1,SHI2;
    int GHI1,GHI2;
    int SKA1,SKA2;
    int GKA1,GKA2;
    int suji,dan,koma;
    SHI1=SHI2=GHI1=GHI2=SKA1=SKA2=GKA1=GKA2=0;
    for(suji=0x10;suji<=0x90;suji+=0x10) {
      for(dan=1;dan<=9;dan++) {
        if (ban[suji+dan]==Koma.SHI) {
          if (SHI1==0) SHI1=suji+dan; else SHI2=suji+dan;
        }
        if (ban[suji+dan]==Koma.GHI) {
          if (GHI1==0) GHI1=suji+dan; else GHI2=suji+dan;
        }
        if (ban[suji+dan]==Koma.SKA) {
          if (SKA1==0) SKA1=suji+dan; else SKA2=suji+dan;
        }
        if (ban[suji+dan]==Koma.GKA) {
          if (GKA1==0) GKA1=suji+dan; else GKA2=suji+dan;
        }
      }
    }
    if (hand[Koma.SHI]==1) if (SHI1==0) SHI1=1; else SHI2=1;
    if (hand[Koma.SHI]==2) SHI1=SHI2=1;
    if (hand[Koma.GHI]==1) if (GHI1==0) GHI1=1; else GHI2=1;
    if (hand[Koma.GHI]==2) GHI1=GHI2=1;
    if (hand[Koma.SKA]==1) if (SKA1==0) SKA1=1; else SKA2=1;
    if (hand[Koma.SKA]==2) SKA1=SKA2=1;
    if (hand[Koma.GKA]==1) if (GKA1==0) GKA1=1; else GKA2=1;
    if (hand[Koma.GKA]==2) GKA1=GKA2=1;
    int Senkei,GyakuSenkei;
    if (SHI1<=0x50 && GHI1<=0x50) {
      Senkei=IvsFURI;
      GyakuSenkei=FURIvsI;
    } else if (0x50<=GHI1 && GHI1<=0x5f && SHI1<=0x50) {
      Senkei=IvsNAKA;
      GyakuSenkei=NAKAvsI;
    } else if (SHI1<=0x5f && GHI1<=0x5f) {
      Senkei=FURIvsFURI;
      GyakuSenkei=FURIvsFURI;
    } else if (GHI1>=0x60 && SHI1>=0x60) {
      Senkei=FURIvsI;
      GyakuSenkei=IvsFURI;
    } else if (0x50<=SHI1 && SHI1<=0x5f && GHI1<=0x50) {
      Senkei=NAKAvsI;
      GyakuSenkei=IvsNAKA;
    } else if (SKA1==1 && GKA1==1) {
      Senkei=KAKUGAWARI;
      GyakuSenkei=KAKUGAWARI;
    } else if (0x20<=SHI1 && SHI1<=0x2f && 0x80<=GHI1 && GHI1<=0x8f) {
      Senkei=AIGAKARI;
      GyakuSenkei=AIGAKARI;
    } else {
      Senkei=HUMEI;
      GyakuSenkei=HUMEI;
    }
    for(suji=0x10;suji<=0x90;suji+=0x10) {
      for(dan=1;dan<=9;dan++) {
        eval-=komagumiValue[ban[suji+dan]][suji+dan];
        for(koma=Koma.SFU;koma<=Koma.GRY;koma++) {
          if (koma==Koma.SGI) {
            JosekiKomagumi[Senkei][koma][suji+dan]=JosekiKomagumiSGI[Senkei][dan-1][9-(suji/0x10)];
          } else if (koma==Koma.GGI) {
            JosekiKomagumi[Senkei][koma][suji+dan]=-JosekiKomagumiSGI[GyakuSenkei][9-dan][suji/0x10-1];
          } else if (koma==Koma.SKI) {
            JosekiKomagumi[Senkei][koma][suji+dan]=JosekiKomagumiSKI[Senkei][dan-1][9-(suji/0x10)];
          } else if (koma==Koma.GKI) {
            JosekiKomagumi[Senkei][koma][suji+dan]=-JosekiKomagumiSKI[GyakuSenkei][9-dan][suji/0x10-1];
          } else if (koma==Koma.SOU) {
            JosekiKomagumi[Senkei][koma][suji+dan]=JosekiKomagumiSOU[Senkei][dan-1][9-(suji/0x10)];
          } else if (koma==Koma.GOU) {
            JosekiKomagumi[Senkei][koma][suji+dan]=-JosekiKomagumiSOU[GyakuSenkei][9-dan][suji/0x10-1];
          } else {
            JosekiKomagumi[Senkei][koma][suji+dan]=DanValue[koma][dan];
          }
          komagumiValue[koma][suji+dan]=JosekiKomagumi[Senkei][koma][suji+dan];
        }
        eval+=komagumiValue[ban[suji+dan]][suji+dan];
      }
    }
  }
  // 自玉近くの自分の金銀の価値
  static final int Mamorigoma[][]={
    { 50, 50, 50, 50, 50, 50, 50, 50, 50},
    { 56, 52, 50, 50, 50, 50, 50, 50, 50},
    { 64, 61, 55, 50, 50, 50, 50, 50, 50},
    { 79, 77, 70, 65, 54, 51, 50, 50, 50},
    {100, 99, 95, 87, 74, 58, 50, 50, 50},
    {116,117,101, 95, 88, 67, 54, 50, 50},
    {131,129,124,114, 90, 71, 59, 51, 50},
    {137,138,132,116, 96, 76, 61, 53, 50},
    {142,142,136,118, 98, 79, 64, 52, 50},
    {132,132,129,109, 95, 75, 60, 51, 50},
    {121,120,105, 97, 84, 66, 54, 50, 50},
    { 95, 93, 89, 75, 68, 58, 51, 50, 50},
    { 79, 76, 69, 60, 53, 50, 50, 50, 50},
    { 64, 61, 55, 51, 50, 50, 50, 50, 50},
    { 56, 52, 50, 50, 50, 50, 50, 50, 50},
    { 50, 50, 50, 50, 50, 50, 50, 50, 50},
    { 50, 50, 50, 50, 50, 50, 50, 50, 50},
  };
  // 相手玉近くの自分の金銀の価値
  static final int Semegoma[][]={
    { 50, 50, 50, 50, 50, 50, 50, 50, 50},
    { 50, 50, 50, 50, 50, 50, 50, 50, 50},
    { 50, 50, 50, 50, 50, 50, 50, 50, 50},
    { 54, 53, 51, 51, 50, 50, 50, 50, 50},
    { 70, 66, 62, 55, 53, 50, 50, 50, 50},
    { 90, 85, 80, 68, 68, 60, 53, 50, 50},
    {100, 97, 95, 85, 84, 71, 51, 50, 50},
    {132,132,129,102, 95, 71, 51, 50, 50},
    {180,145,137,115, 91, 75, 57, 50, 50},
    {170,165,150,121, 94, 78, 58, 52, 50},
    {170,160,142,114, 98, 80, 62, 55, 50},
    {140,130,110,100, 95, 75, 54, 50, 50},
    {100, 99, 95, 87, 78, 69, 50, 50, 50},
    { 80, 78, 72, 67, 55, 51, 50, 50, 50},
    { 62, 60, 58, 52, 50, 50, 50, 50, 50},
    { 50, 50, 50, 50, 50, 50, 50, 50, 50},
    { 50, 50, 50, 50, 50, 50, 50, 50, 50},
  };
  // 絶対値を求める。
  static int abs(int x) {
    if (x<0) return -x;
    return x;
  }
  // 金駒の価値の計算をする。
  void initKanagomaValue() {
    for(int kingSdan=1;kingSdan<=9;kingSdan++) {
      for(int kingSsuji=0x10;kingSsuji<=0x90;kingSsuji+=0x10) {
        for(int kingEdan=1;kingEdan<=9;kingEdan++) {
          for(int kingEsuji=0x10;kingEsuji<=0x90;kingEsuji+=0x10) {
            for(int suji=0x10;suji<=0x90;suji+=0x10) {
              for(int dan=1;dan<=9;dan++) {
                int DiffSujiS=abs(kingSsuji-suji)/0x10;
                int DiffSujiE=abs(kingEsuji-suji)/0x10;
                int DiffDanSS=8+(dan-kingSdan);
                int DiffDanES=8+(dan-kingEdan);
                int DiffDanSE=8+(-(dan-kingSdan));
                int DiffDanEE=8+(-(dan-kingEdan));
                int kingS=kingSsuji+kingSdan;
                int kingE=kingEsuji+kingEdan;
                SemegomaValueS[suji+dan][kingE]=Semegoma[DiffDanES][DiffSujiE]-100;
                MamorigomaValueS[suji+dan][kingS]=Mamorigoma[DiffDanSS][DiffSujiS]-100;
                SemegomaValueE[suji+dan][kingS]=-(Semegoma[DiffDanSE][DiffSujiS]-100);
                MamorigomaValueE[suji+dan][kingE]=-(Mamorigoma[DiffDanEE][DiffSujiE]-100);
              }
            }
          }
        }
      }
    }
  }
  // 攻め駒が近付くと終盤度がこれだけあがる。
  static final int ShuubandoByAtack[]={
  //空歩香桂銀金角飛王と杏圭全金馬龍
    0,1,1,2,3,3,3,4,4,3,3,3,3,3,4,5
  };
  // 守り駒がいれば、終盤度がこれだけ下がる
  static final int ShuubandoByDefence[]={
  //空歩香桂銀 金角 飛王 と 杏 圭 全 金 馬 龍
    0,0,0,0,-1,-1,0,-1,0,-1,-1,-1,-1,-1,-2,-1
  };
  // 手持ちの駒による終盤度の上昇
  static final int ShuubandoByHand[]={
  //空歩香桂銀金角飛王と杏圭全金馬龍
    0,0,1,1,2,2,2,3,0,0,0,0,0,0,0,0
  };
  static int SemegomaValueS[][]=new int[16*11][16*11];
  static int SemegomaValueE[][]=new int[16*11][16*11];
  static int MamorigomaValueS[][]=new int[16*11][16*11];
  static int MamorigomaValueE[][]=new int[16*11][16*11];
  static {
    for(int kingSdan=1;kingSdan<=9;kingSdan++) {
      for(int kingSsuji=0x10;kingSsuji<=0x90;kingSsuji+=0x10) {
        for(int kingEdan=1;kingEdan<=9;kingEdan++) {
          for(int kingEsuji=0x10;kingEsuji<=0x90;kingEsuji+=0x10) {
            for(int suji=0x10;suji<=0x90;suji+=0x10) {
              for(int dan=1;dan<=9;dan++) {
                int DiffSujiS=abs(kingSsuji-suji)/0x10;
                int DiffSujiE=abs(kingEsuji-suji)/0x10;
                int DiffDanSS=8+(dan-kingSdan);
                int DiffDanES=8+(dan-kingEdan);
                int DiffDanSE=8+(-(dan-kingSdan));
                int DiffDanEE=8+(-(dan-kingEdan));
                int kingS=kingSsuji+kingSdan;
                int kingE=kingEsuji+kingEdan;
                SemegomaValueS[suji+dan][kingE]=Semegoma[DiffDanES][DiffSujiE]-100;
                MamorigomaValueS[suji+dan][kingS]=Mamorigoma[DiffDanSS][DiffSujiS]-100;
                SemegomaValueE[suji+dan][kingS]=-(Semegoma[DiffDanSE][DiffSujiS]-100);
                MamorigomaValueE[suji+dan][kingE]=-(Mamorigoma[DiffDanEE][DiffSujiE]-100);
              }
            }
          }
        }
      }
    }
  }
  int Shuubando[]=new int[2];
  int SemegomaBonus[]=new int[2];
  int MamorigomaBonus[]=new int[2];
  // 終盤度の計算
  void initShuubando() {
    // 終盤度を求めると同時に、終盤度によるボーナスの付加、駒の加点も行う。
    int suji,dan;
    Shuubando[0]=0;
    Shuubando[1]=0;
    for(suji=0x10;suji<=0x90;suji+=0x10) {
      for(dan=1;dan<=4;dan++) {
        if (Koma.isSente(ban[suji+dan])) {
          Shuubando[1]+=ShuubandoByAtack[ban[suji+dan] & ~SENTE];
        }
        if (Koma.isGote(ban[suji+dan])) {
          Shuubando[1]+=ShuubandoByDefence[ban[suji+dan] & ~GOTE];
        }
      }
      for(dan=6;dan<=9;dan++) {
        if (Koma.isGote(ban[suji+dan])) {
          Shuubando[0]+=ShuubandoByAtack[ban[suji+dan] & ~GOTE];
        }
        if (Koma.isSente(ban[suji+dan])) {
          Shuubando[0]+=ShuubandoByDefence[ban[suji+dan] & ~SENTE];
        }
      }
    }
    int koma;
    for(koma=Koma.FU;koma<=Koma.HI;koma++) {
      Shuubando[0]+=ShuubandoByHand[koma]*hand[GOTE|koma];
      Shuubando[1]+=ShuubandoByHand[koma]*hand[SENTE|koma];
    }
  }
  static final int IsKanagoma[]={
//  空空空空空空空空空空空空空空空空空歩香桂銀金角飛王と杏圭全金馬龍
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,0,0,0,1,1,1,1,1,0,0,
//  空歩香桂銀金角飛王と杏圭全金馬龍壁空空空空空空空空空空空空空空空
    0,0,0,0,1,1,0,0,0,1,1,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0
  };
  void initBonus() {
    int suji,dan;
    SemegomaBonus[0]=SemegomaBonus[1]=0;
    MamorigomaBonus[0]=MamorigomaBonus[1]=0;
    for(suji=0x10;suji<=0x90;suji+=0x10) {
      for(dan=1;dan<=9;dan++) {
        if (IsKanagoma[ban[suji+dan]]!=0) {
          if (Koma.isSente(ban[suji+dan])) {
            SemegomaBonus[0]+=SemegomaValueS[suji+dan][kingG];
            MamorigomaBonus[0]+=MamorigomaValueS[suji+dan][kingS];
          } else {
            SemegomaBonus[1]+=SemegomaValueE[suji+dan][kingS];
            MamorigomaBonus[1]+=MamorigomaValueE[suji+dan][kingG];
          }
        }
      }
    }
  }
  // 必要な変数を初期化する。
  public void initAll() {
    initTbl();
    senkeiInit();
    initShuubando();
    initBonus();
    super.initAll();
  }
  public void move(Te te) {
    int self,enemy;
    if (Koma.isSente(te.koma)) {
      self=0;
      enemy=1;
    } else {
      self=1;
      enemy=0;
    }
    if (te.koma==Koma.SOU || te.koma==Koma.GOU) {
    } else {
      if (IsKanagoma[te.koma]!=0 && te.from>0) {
        if (self==0) {
          SemegomaBonus[0]-=SemegomaValueS[te.from][kingG];
          MamorigomaBonus[0]-=MamorigomaValueS[te.from][kingS];
        } else {
          SemegomaBonus[1]-=SemegomaValueE[te.from][kingS];
          MamorigomaBonus[1]-=MamorigomaValueE[te.from][kingG];
        }
      }
      if (te.capture!=Koma.EMPTY) {
        if (IsKanagoma[te.capture]!=0) {
          if (self==0) {
            SemegomaBonus[1]-=SemegomaValueE[te.to][kingS];
            MamorigomaBonus[1]-=MamorigomaValueE[te.to][kingG];
          } else {
            SemegomaBonus[0]-=SemegomaValueS[te.to][kingG];
            MamorigomaBonus[0]-=MamorigomaValueS[te.to][kingS];
          }
        }
      }
      if (!te.promote) {
        if (IsKanagoma[te.koma]!=0) {
          if (self==0) {
            SemegomaBonus[0]+=SemegomaValueS[te.to][kingG];
            MamorigomaBonus[0]+=MamorigomaValueS[te.to][kingS];
          } else {
            SemegomaBonus[1]+=SemegomaValueE[te.to][kingS];
            MamorigomaBonus[1]+=MamorigomaValueE[te.to][kingG];
          }
        }
      } else {
        if (IsKanagoma[te.koma|Koma.PROMOTE]!=0) {
          if (self==0) {
            SemegomaBonus[0]+=SemegomaValueS[te.to][kingG];
            MamorigomaBonus[0]+=MamorigomaValueS[te.to][kingS];
          } else {
            SemegomaBonus[1]+=SemegomaValueE[te.to][kingS];
            MamorigomaBonus[1]+=MamorigomaValueE[te.to][kingG];
          }
        }
      }
    }
    if (te.from>0 && (te.from&0x0f)<=4) {
      // ４段目以下・終盤度の計算
      if (self==0) {
        Shuubando[1]-=ShuubandoByAtack[te.koma & ~SENTE];
      } else {
        Shuubando[1]-=ShuubandoByDefence[te.koma & ~GOTE];
      }
    }
    if (te.from>0 && (te.from&0x0f)>=6) {
      // ６段目以上・終盤度の計算
      if (self==0) {
        Shuubando[0]-=ShuubandoByDefence[te.koma & ~SENTE];
      } else {
        Shuubando[0]-=ShuubandoByAtack[te.koma & ~GOTE];
      }
    }
    if (te.from==0) {
      // 打つことによる終盤度の減少
      if (self==0) {
        Shuubando[1]-=ShuubandoByHand[te.koma&~SENTE];
      } else {
        Shuubando[0]-=ShuubandoByHand[te.koma&~GOTE];
      }
    }
    if (te.capture!=Koma.EMPTY) {
      if ((te.to&0x0f)<=4) {
        // ４段目以下・終盤度の計算
        if (self==0) {
          Shuubando[1]-=ShuubandoByDefence[te.capture & ~GOTE];
        } else {
          Shuubando[1]-=ShuubandoByAtack[te.capture & ~SENTE];
        }
      }
      if ((te.to&0x0f)>=6) {
        // ６段目以上・終盤度の計算
        if (self==0) {
          Shuubando[0]-=ShuubandoByAtack[te.capture & ~GOTE];
        } else {
          Shuubando[0]-=ShuubandoByDefence[te.capture & ~SENTE];
        }
      }
      // Handに入ったことによる終盤度の計算
      if (self==0) {
        Shuubando[1]+=ShuubandoByHand[te.capture&~GOTE&~Koma.PROMOTE];
      } else {
        Shuubando[0]+=ShuubandoByHand[te.capture&~SENTE&~Koma.PROMOTE];
      }
    }
    if (!te.promote) {
      if ((te.to&0x0f)<=4) {
        // ４段目以下・終盤度の計算
        if (self==0) {
          Shuubando[1]+=ShuubandoByAtack[te.koma & ~SENTE];
        } else {
          Shuubando[1]+=ShuubandoByDefence[te.koma & ~GOTE];
        }
      }
      if ((te.to&0x0f)>=6) {
        // ６段目以上・終盤度の計算
        if (self==0) {
          Shuubando[0]+=ShuubandoByDefence[te.koma & ~SENTE];
        } else {
          Shuubando[0]+=ShuubandoByAtack[te.koma & ~GOTE];
        }
      }
    } else {
      if ((te.to&0x0f)<=4) {
        // ４段目以下・終盤度の計算
        if (self==0) {
          Shuubando[1]+=ShuubandoByAtack[(te.koma|Koma.PROMOTE) & ~SENTE];
        } else {
          Shuubando[1]+=ShuubandoByDefence[(te.koma|Koma.PROMOTE) & ~GOTE];
        }
      }
      if ((te.to&0x0f)>=6) {
        // ６段目以上・終盤度の計算
        if (self==0) {
          Shuubando[0]+=ShuubandoByDefence[(te.koma|Koma.PROMOTE) & ~SENTE];
        } else {
          Shuubando[0]+=ShuubandoByAtack[(te.koma|Koma.PROMOTE) & ~GOTE];
        }
      }
    }
    eval-=komagumiValue[te.koma][te.from];
    if (te.capture!=Koma.EMPTY) {
      eval-=komagumiValue[te.capture][te.to];
    }
    if (!te.promote) {
      eval+=komagumiValue[te.koma][te.to];
    } else {
      eval+=komagumiValue[te.koma|Koma.PROMOTE][te.to];
    }
    super.move(te);
    if (te.koma==Koma.SOU || te.koma==Koma.GOU) {
      // 全面的に金駒のBonusの計算しなおし。
      initBonus();
    }
  }
  public void back(Te te) {
    int self,enemy;
    if (Koma.isSente(te.koma)) {
      self=0;
      enemy=1;
    } else {
      self=1;
      enemy=0;
    }
    if (te.koma==Koma.SOU || te.koma==Koma.GOU) {
    } else {
      if (IsKanagoma[te.koma]!=0 && te.from>0) {
        if (self==0) {
          SemegomaBonus[0]+=SemegomaValueS[te.from][kingG];
          MamorigomaBonus[0]+=MamorigomaValueS[te.from][kingS];
        } else {
          SemegomaBonus[1]+=SemegomaValueE[te.from][kingS];
          MamorigomaBonus[1]+=MamorigomaValueE[te.from][kingG];
        }
      }
      if (te.capture!=Koma.EMPTY) {
        if (IsKanagoma[te.capture]!=0) {
          if (self==0) {
            SemegomaBonus[1]+=SemegomaValueE[te.to][kingS];
            MamorigomaBonus[1]+=MamorigomaValueE[te.to][kingG];
          } else {
            SemegomaBonus[0]+=SemegomaValueS[te.to][kingG];
            MamorigomaBonus[0]+=MamorigomaValueS[te.to][kingS];
          }
        }
      }
      if (!te.promote) {
        if (IsKanagoma[te.koma]!=0) {
          if (self==0) {
            SemegomaBonus[0]-=SemegomaValueS[te.to][kingG];
            MamorigomaBonus[0]-=MamorigomaValueS[te.to][kingS];
          } else {
            SemegomaBonus[1]-=SemegomaValueE[te.to][kingS];
            MamorigomaBonus[1]-=MamorigomaValueE[te.to][kingG];
          }
        }
      } else {
        if (IsKanagoma[te.koma|Koma.PROMOTE]!=0) {
          if (self==0) {
            SemegomaBonus[0]-=SemegomaValueS[te.to][kingG];
            MamorigomaBonus[0]-=MamorigomaValueS[te.to][kingS];
          } else {
            SemegomaBonus[1]-=SemegomaValueE[te.to][kingS];
            MamorigomaBonus[1]-=MamorigomaValueE[te.to][kingG];
          }
        }
      }
    }
    if (te.from>0 && (te.from&0x0f)<=4) {
      // ４段目以下・終盤度の計算
      if (self==0) {
        Shuubando[1]+=ShuubandoByAtack[te.koma & ~SENTE];
      } else {
        Shuubando[1]+=ShuubandoByDefence[te.koma & ~GOTE];
      }
    }
    if (te.from>0 && (te.from&0x0f)>=6) {
      // ６段目以上・終盤度の計算
      if (self==0) {
        Shuubando[0]+=ShuubandoByDefence[te.koma & ~SENTE];
      } else {
        Shuubando[0]+=ShuubandoByAtack[te.koma & ~GOTE];
      }
    }
    if (te.from==0) {
      // 打つことによる終盤度の減少
      if (self==0) {
        Shuubando[1]+=ShuubandoByHand[te.koma&~SENTE];
      } else {
        Shuubando[0]+=ShuubandoByHand[te.koma&~GOTE];
      }
    }
    if (te.capture!=Koma.EMPTY) {
      if ((te.to&0x0f)<=4) {
        // ４段目以下・終盤度の計算
        if (self==0) {
          Shuubando[1]+=ShuubandoByDefence[te.capture & ~GOTE];
        } else {
          Shuubando[1]+=ShuubandoByAtack[te.capture & ~SENTE];
        }
      }
      if ((te.to&0x0f)>=6) {
        // ６段目以上・終盤度の計算
        if (self==0) {
          Shuubando[0]+=ShuubandoByAtack[te.capture & ~GOTE];
        } else {
          Shuubando[0]+=ShuubandoByDefence[te.capture & ~SENTE];
        }
      }
      // Handに入ったことによる終盤度の計算
      if (self==0) {
        Shuubando[1]-=ShuubandoByHand[te.capture&~GOTE&~Koma.PROMOTE];
      } else {
        Shuubando[0]-=ShuubandoByHand[te.capture&~SENTE&~Koma.PROMOTE];
      }
    }
    if (!te.promote) {
      if ((te.to&0x0f)<=4) {
        // ４段目以下・終盤度の計算
        if (self==0) {
          Shuubando[1]-=ShuubandoByAtack[te.koma & ~SENTE];
        } else {
          Shuubando[1]-=ShuubandoByDefence[te.koma & ~GOTE];
        }
      }
      if ((te.to&0x0f)>=6) {
        // ６段目以上・終盤度の計算
        if (self==0) {
          Shuubando[0]-=ShuubandoByDefence[te.koma & ~SENTE];
        } else {
          Shuubando[0]-=ShuubandoByAtack[te.koma & ~GOTE];
        }
      }
    } else {
      if ((te.to&0x0f)<=4) {
        // ４段目以下・終盤度の計算
        if (self==0) {
          Shuubando[1]-=ShuubandoByAtack[(te.koma|Koma.PROMOTE) & ~SENTE];
        } else {
          Shuubando[1]-=ShuubandoByDefence[(te.koma|Koma.PROMOTE) & ~GOTE];
        }
      }
      if ((te.to&0x0f)>=6) {
        // ６段目以上・終盤度の計算
        if (self==0) {
          Shuubando[0]-=ShuubandoByDefence[(te.koma|Koma.PROMOTE) & ~SENTE];
        } else {
          Shuubando[0]-=ShuubandoByAtack[(te.koma|Koma.PROMOTE) & ~GOTE];
        }
      }
    }
    eval+=komagumiValue[te.koma][te.from];
    if (te.capture!=Koma.EMPTY) {
      eval+=komagumiValue[te.capture][te.to];
    }
    if (!te.promote) {
      eval-=komagumiValue[te.koma][te.to];
    } else {
      eval-=komagumiValue[te.koma|Koma.PROMOTE][te.to];
    }
    super.back(te);
    if (te.koma==Koma.SOU || te.koma==Koma.GOU) {
      // 全面的に金駒のBonusの計算しなおし。
      initBonus();
    }
  }
  // 局面を評価する関数。
  public int evaluate() {
    // 終盤度を０〜１６の範囲に補正する。
    int Shuubando0,Shuubando1;
    if (Shuubando[0]<0) {
      Shuubando0=0;
    } else if (Shuubando[0]>16) {
      Shuubando0=16;
    } else {
      Shuubando0=Shuubando[0];
    }
    if (Shuubando[1]<0) {
      Shuubando1=0;
    } else if (Shuubando[1]>16) {
      Shuubando1=16;
    } else {
      Shuubando1=Shuubando[1];
    }
    int ret=0;
    ret+=SemegomaBonus[0]*Shuubando1/16;
    ret+=MamorigomaBonus[0]*Shuubando0/16;
    ret+=SemegomaBonus[1]*Shuubando0/16;
    ret+=MamorigomaBonus[1]*Shuubando1/16;
    return eval+ret;
  }
}
class Lan implements Player,Constants {
  CSAProtocol csaProtocol;
  public Lan(CSAProtocol p) {
    csaProtocol=p;
  }
  public Te getNextTe(Kyokumen k,int tesu,int spenttime,int limittime,int byoyomi) {
    Te t=new Te();
    try {
      t=csaProtocol.recvTe();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return t;
  }
}
public class Shogi implements Constants {
  // 初期盤面を与える
  static final int ShokiBanmen[][]={
    {Koma.GKY,Koma.GKE,Koma.GGI,Koma.GKI,Koma.GOU,Koma.GKI,Koma.GGI,Koma.GKE,Koma.GKY},
    {Koma.EMP,Koma.GHI,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.GKA,Koma.EMP},
    {Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU,Koma.GFU},
    {Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP},
    {Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP},
    {Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP},
    {Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU,Koma.SFU},
    {Koma.EMP,Koma.SKA,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.EMP,Koma.SHI,Koma.EMP},
    {Koma.SKY,Koma.SKE,Koma.SGI,Koma.SKI,Koma.SOU,Koma.SKI,Koma.SGI,Koma.SKE,Koma.SKY},
  };
  // player[0]が先手が誰か、player[1]が後手が誰か。
  static Player player[]=new Player[2];
  // 今までに使った累積時間（秒）
  static int spenttime[]=new int[2];
  // 思考時間の上限（秒）…大会ルールにあわせ、25分
  static int limitTime=1500;
  // 思考時間を過ぎた後の秒読み…大会ルールにあわせ、0
  static int byoyomi=0;
  static Vector kyokumenRireki=new Vector();
  // 使い方を表示する。
  static void usage() {
    System.out.println("使い方：");
    System.out.println("例：先手人間後手コンピュータの場合");
    System.out.println("java jp.usapyonsoft.lesserpyon.Main HUMAN CPU");
    System.out.println("");
    System.out.println("初期局面を与えて対局開始することも可能です。");
    System.out.println("例：kyokumen.csaに初期局面が入っているとした場合");
    System.out.println("java jp.usapyonsoft.lesserpyon.Main HUMAN CPU kyokumen.csa");
    System.out.println("LAN対戦の場合、先手・後手が入れ替わることがあります。");
    System.out.println("例：先手LAN後手コンピュータの場合");
    System.out.println("java jp.usapyonsoft.lesserpyon.Main LAN CPU");
    System.out.println("この場合、ログインしてから先手・後手が決まります。");
    System.out.println("また、先手・後手の両方にLANを指定することは出来ません。");
  }
  static CSAProtocol csaProtocol;
  static final String server="wdoor.c.u-tokyo.ac.jp";
  static final int serverPort=4081;
  static final String myName="lesserpyon-fo-java";
  static final String myPassword="yowai_gps-1500-0,ikeike";
  // メイン関数引数は、先手番が誰か、後手番が誰か、初期局面は何か。
  // 誰か、は、人間ならば "HUMAN" CPUならば "CPU"を与える。
  // 初期局面が与えられなかった場合、平手の初期局面から。
  public static void main(String argv[]) {
    // 先手番、後手番が引数で与えられているかどうかチェック
    if (argv.length<2) {
      // 引数が足りないようなら、使い方を表示して終わり。
      usage();
      return;
    }
    Kyokumen k=new Kyokumen();
    int myTurn=SENTE;
    boolean isLan=false;
    if (argv[0].equals("LAN") || argv[1].equals("LAN")) {
      // 引数のチェック
      if (argv[0].equals("LAN")) {
        if (!argv[1].equals("CPU") && !argv[1].equals("HUMAN")) {
          usage();
          return;
        }
      } else {
        if (!argv[0].equals("CPU") && !argv[0].equals("HUMAN")) {
          usage();
          return;
        }
      }
      isLan=true;
      try {
        System.out.println("ＬＡＮに接続を試みます…");
        csaProtocol=new CSAProtocol(server,serverPort);
        System.out.println("接続成功です。");
        System.out.println("ログインを試みます…");
        if (csaProtocol.login(myName,myPassword)==0) {
          System.out.println("ログイン失敗です。");
          return;
        }
        System.out.println("ログイン成功です。");
        System.out.println("ゲームの開始を待ちます…");
        myTurn=csaProtocol.waitGameStart(k);
        System.out.println("ゲーム開始です。");
        if (myTurn==SENTE) {
          System.out.println("先手になりました。");
        } else {
          System.out.println("後手になりました。");
        }
      } catch(IOException e) {
        e.printStackTrace();
        System.out.println("接続できませんでした。");
        return;
      }
    }
    if (isLan) {
      if (myTurn==SENTE) {
        if (argv[0].equals("HUMAN") || argv[1].equals("HUMAN")) {
          player[0]=new Human();
        }
        if (argv[0].equals("CPU") || argv[1].equals("CPU")) {
          player[0]=new Sikou();
        }
        player[1]=new Lan(csaProtocol);
      } else {
        player[0]=new Lan(csaProtocol);
        if (argv[0].equals("HUMAN") || argv[1].equals("HUMAN")) {
          player[1]=new Human();
        }
        if (argv[0].equals("CPU") || argv[1].equals("CPU")) {
          player[1]=new Sikou();
        }
      }
    } else {
      // 先手番が誰かを設定。
      if (argv[0].equals("HUMAN")) {
        player[0]=new Human();
      } else if (argv[0].equals("CPU")) {
        player[0]=new Sikou();
      } else {
        // 引数がおかしいようなら、使い方を表示して終わり。
        usage();
        return;
      }
      // 後手番が誰かを設定。
      if (argv[1].equals("HUMAN")) {
        player[1]=new Human();
      } else if (argv[1].equals("CPU")) {
        player[1]=new Sikou();
      } else {
        // 引数がおかしいようなら、使い方を表示して終わり。
        usage();
        return;
      }
    }
    try {
      if (isLan) {
        // 局面の初期化が行われている。
      } else if (argv.length==2) {
        // 引数の指定がない場合、初期配置を使う。
        k.initHirate();
      } else {
        // 引数で指定があった場合、CSA形式の棋譜ファイルを読み込む。
        String csaFileName=argv[2];
        File f=new File(csaFileName);
        BufferedReader in=new BufferedReader(new FileReader(f));
        Vector v=new Vector();
        String s;
        while((s=in.readLine())!=null) {
          System.out.println("Read:"+s);
          v.add(s);
        }
        String csaKifu[]=new String[v.size()];
        v.copyInto(csaKifu);
        k.ReadCsaKifu(csaKifu);
        // ReadCsaKifuの中で必要な初期化が行われている。
      }
      int tesu=0;
      // 対戦のメインループ
      while(true) {
        k.tesu++;
        tesu++;
        // 現在の局面を、局面の履歴に保存する。
        kyokumenRireki.add(k.clone());
        // 現在の局面での合法手を生成
        Vector v=GenerateMoves.generateLegalMoves(k);
        if (v.size()==0) {
          // 手番の側の負け
          if (k.teban==SENTE) {
            System.out.println("後手の勝ち！");
          } else {
            System.out.println("先手の勝ち！");
          }
          // 対局終了
          break;
        }
        // 千日手のチェック…連続王手の千日手には未対応。
        // 同一局面が何回出てきたか？
        int sameKyokumen=0;
        for(int i=0;i<kyokumenRireki.size();i++) {
          // 同一局面だったら…
          if (kyokumenRireki.elementAt(i).equals(k)) {
            // 同一局面の出てきた回数を増やす
            sameKyokumen++;
          }
        }
        if (sameKyokumen>=4) {
          // 同一局面４回以上の繰り返しなので、千日手。
          System.out.println("千日手です。");
          // 対局終了
          break;
        }
        // 局面を表示。
        System.out.println(k.toString());
        // ついでに、局面の評価値を表示
        System.out.println("現在の局面の評価値:"+k.evaluate());
        // 次の手を手番側のプレイヤーから取得
        Te te;
        long ltime=System.currentTimeMillis();
        if (k.teban==SENTE) {
          te=player[0].getNextTe(k,k.tesu,k.spentTime[0],limitTime,byoyomi);
          if (isLan) {
            if (myTurn==SENTE) {
              if (te.koma==0) {
                csaProtocol.resign();
              } else {
                csaProtocol.sendTe(te);
              }
            }
            k.spentTime[0]+=csaProtocol.time[0];
          } else {
            int spent=(int)((System.currentTimeMillis()-ltime)/1000);
            if (spent==0) spent=1;
            k.spentTime[0]+=spent;
          }
        } else {
          te=player[1].getNextTe(k,k.tesu,k.spentTime[1],limitTime,byoyomi);
          if (isLan) {
            if (myTurn==GOTE) {
              if (te.koma==0) {
                csaProtocol.resign();
              } else {
                csaProtocol.sendTe(te);
              }
            }
            k.spentTime[1]+=csaProtocol.time[1];
          } else {
            int spent=(int)((System.currentTimeMillis()-ltime)/1000);
            if (spent==0) spent=1;
            k.spentTime[1]+=spent;
          }
        }
        // 指された手を表示
        System.out.println(te.toString());
        // 合法手でない手を指した場合、即負け
        if (!v.contains(te)) {
          System.out.println("合法手でない手が指されました。");
          // 手番の側の負け
          if (k.teban==SENTE) {
            System.out.println("後手の勝ち！");
          } else {
            System.out.println("先手の勝ち！");
          }
          // 対局終了
          break;
        }
        // 指された手で局面を進める。
        k.move(te);
        // moveでは、手番が変わらないので、局面の手番を変更する。
        if (k.teban==SENTE) {
          k.teban=GOTE;
        } else {
          k.teban=SENTE;
        }
      }
      // 対局終了。最後の局面を表示して、終わる。
      System.out.println("対局終了です。");
      System.out.println("最後の局面は…");
      System.out.println(k.toString());
      if (isLan) {
        csaProtocol.logout();
      }
    } catch(Exception ex) {
      ex.printStackTrace();
    }
  }
}
interface Player {
  // 次の手を返す関数。
  // 引数は、現在の局面、手数、今までの累積思考時間、与えられた思考時間、
  // 思考時間が切れた後の秒読み
  Te getNextTe(Kyokumen k,int tesu,int spenttime,int limittime,int byoyomi);
}
// コンピュータの思考ルーチン
class Sikou implements Player,Constants,Runnable {
  // ∞を表すための定数
  static final int INFINITE=99999999;
  static final int STOPPED=111111111;
  // 読みの深さ
  static final int DEPTH_MAX=6;
  // 読みの最大深さ…これ以上の読みは絶対に不可能。
  static final int LIMIT_DEPTH=16;
  // 最大読み手数
  static final int teMax[]={50,50,32,32,24,24,16,16,16,16,16,16,16,16,16,16};
  // αβカットを起こす関係で、ランダム着手は出来なくなる。
  // 詳細は解説にて。
  // 最善手順を格納する配列
  public Te best[][]=new Te[LIMIT_DEPTH][LIMIT_DEPTH];
  // ここまでの探索手順を格納する配列
  public Te stack[]=new Te[LIMIT_DEPTH];
  // この思考用のTransPositionTable
  TranspositionTable tt=new TranspositionTable();
  int leaf=0;
  int node=0;
  // 定跡があれば定跡を利用。
  Joseki joseki;
  // 裏側で思考するための盤
  KyokumenKomagumi kk;
  // 予測読みを開始した盤
  KyokumenKomagumi yosoku;
  // 予測読みをするスレッド
  Thread sikou_ura;
  // 『手の評価』で使う手の配列
  Te teS[]={
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te()
  };
  // 後手側
  Te teE[]={
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te(),
    new Te(),new Te(),new Te(),new Te(),new Te()
  };
  // 思考を止める。
  volatile boolean stop;
  volatile boolean stopped;
  // 裏思考が終了している。
  volatile boolean processed;
  // 思考を開始した時間
  long time;
  long sikoutime;
  public Sikou() {
    joseki=new Joseki("joseki.bin");
  }
  int min(int x,int y) {
    if (x<y) return x;
    return y;
  }
  int negaMax(Te t,Kyokumen k,int alpha,int beta,int depth,int depthMax,
      boolean bITDeep) {
    // 深さが最大深さに達していたらそこでの評価値を返して終了。
    if (depth>=depthMax) {
      leaf++;
      if (k.teban==SENTE) {
        return k.evaluate();
      } else {
        return -k.evaluate();
      }
    }
    if (stop) {
      return STOPPED;
    }
    node++;
    TTEntry e=tt.get(k.HashVal);
    if (e!=null) {
      if (e.value>=beta && e.depth<=depth && e.remainDepth>=depthMax-depth &&
          e.flag!=TTEntry.UPPER_BOUND) {
        return e.value;
      }
      if (e.value<=alpha && e.depth<=depth && e.remainDepth>=depthMax-depth &&
          e.flag!=TTEntry.LOWER_BOUND) {
        return e.value;
      }
    }
    if (e==null && depthMax-depth>2 && bITDeep) {
      return ITDeep(k,alpha,beta,depth,depthMax);
    }
    // 現在の指し手の候補手の評価値を入れる。
    int value=-INFINITE;
    // 最初に軽い手の生成をしてみる。
    Vector v=GenerateMoves.makeMoveFirst(k,depth,this,e);
    for(int i=0;i<v.size();i++) {
      // 合法手を取り出す。
      Te te=(Te)v.elementAt(i);
      // その手で一手進める。
      stack[depth]=te;
      k.move(te);
      // moveでは、先手後手を入れ替えないので…。
      if (k.teban==SENTE) {
        k.teban=GOTE;
      } else {
        k.teban=SENTE;
      }
      // その局面の評価値を、さらに先読みして得る。
      Te tmpTe=new Te(0,0,0,false,0);
      int eval=-negaMax(tmpTe,k,-beta,-alpha,depth+1,depthMax,true);
      k.back(te);
      if (stop) return STOPPED;
      // backでは、先手後手を入れ替えないので…。
      if (k.teban==SENTE) {
        k.teban=GOTE;
      } else {
        k.teban=SENTE;
      }
      // 指した手で進めた局面が、今までよりもっと大きな値を返すか？
      if (eval>value) {
        // 返す値を更新
        value=eval;
        // α値も更新
        if (eval>alpha) {
          alpha=eval;
        }
        // 最善手を更新
        best[depth][depth]=te;
        t.koma   =te.koma;
        t.from   =te.from;
        t.to     =te.to;
        t.promote=te.promote;
        // 最善手順を更新
        for(int j=depth+1;j<depthMax;j++) {
          best[depth][j]=best[depth+1][j];
        }
        if (depth==0) {
          System.out.print("経過時間"+(System.currentTimeMillis()-time)+"ms  評価値:"+value);
          System.out.print("  最善手順:");
          for(int j=0;j<depthMax;j++) {
            System.out.print(best[0][j]);
          }
          System.out.println();
        }
        // βカットの条件を満たしていたら、ループ終了。
        if (eval>=beta) {
          tt.add(k.HashVal,value,alpha,beta,best[depth][depth],
              depth,depthMax-depth,0);
          return eval;
        }
      }
      if (depth==0 && value>-INFINITE && System.currentTimeMillis()-time>sikoutime) {
        break;
      }
    }
    // 現在の局面での合法手を生成
    v=GenerateMoves.generateLegalMoves(k);
    GenerateMoves.evaluateTe(k,v,teS,teE);
    // 合法手の中から、一手指してみて、一番よかった指し手を選択。
    for(int i=0;i<v.size();i++) {
      // 合法手を取り出す。
      Te te=(Te)v.elementAt(i);
      if ((te.value<-100 || i>teMax[depth]) && value>-INFINITE) {
        break;
      }
      // その手で一手進める。
      stack[depth]=te;
      k.move(te);
      // moveでは、先手後手を入れ替えないので…。
      if (k.teban==SENTE) {
        k.teban=GOTE;
      } else {
        k.teban=SENTE;
      }
      // その局面の評価値を、さらに先読みして得る。
      Te tmpTe=new Te(0,0,0,false,0);
      int eval=-negaMax(tmpTe,k,-beta,-alpha,depth+1,depthMax,true);
      k.back(te);
      if (depth>1 && te.to==stack[depth-1].to && stack[depth-1].value2<-100 && eval<beta) {
        // 水平線効果くさい手…
        // 延長探索
        /*
        System.out.print("延長探索: eval="+eval+" ");
        for(int j=0;j<=depth;j++){
          if (j==depth-1) {
            System.out.print("＊");
          }
          System.out.print(stack[j]);
          if (j==depth-1) {
            System.out.print("："+stack[depth-1].value2);
          }
        }
        for(int j=depth+1;j<depthMax;j++) {
          System.out.print(best[depth][j]);
        }
        System.out.println();
        */
        k.move(te);
        int enchou=-negaMax(tmpTe,k,-beta,-beta+1,depth+1,min(depthMax+2,LIMIT_DEPTH),true);
        if (enchou>beta) {
          eval=enchou;
        }
        k.back(te);
      }
      if (stop) return STOPPED;
      // backでは、先手後手を入れ替えないので…。
      if (k.teban==SENTE) {
        k.teban=GOTE;
      } else {
        k.teban=SENTE;
      }
      // 指した手で進めた局面が、今までよりもっと大きな値を返すか？
      if (eval>value) {
        // 返す値を更新
        value=eval;
        // α値も更新
        if (eval>alpha) {
          alpha=eval;
        }
        // 最善手を更新
        best[depth][depth]=te;
        t.koma   =te.koma;
        t.from   =te.from;
        t.to     =te.to;
        t.promote=te.promote;
        // 最善手順を更新
        for(int j=depth+1;j<depthMax;j++) {
          best[depth][j]=best[depth+1][j];
        }
        if (depth==0) {
          System.out.print("経過時間"+(System.currentTimeMillis()-time)+"ms  評価値:"+value);
          System.out.print("  最善手順:");
          for(int j=0;j<depthMax;j++) {
            System.out.print(best[0][j]);
          }
          System.out.println();
        }
        // βカットの条件を満たしていたら、ループ終了。
        if (eval>=beta) {
          break;
        }
      }
      if (depth==0 && value>-INFINITE && System.currentTimeMillis()-time>sikoutime) {
        break;
      }
    }
    tt.add(k.HashVal,value,alpha,beta,best[depth][depth],
        depth,depthMax-depth,0);
    return value;
  }
  int ITDeep(Kyokumen k,int alpha,int beta,int depth,int depthMax) {
    if (depth==0) {
      time=System.currentTimeMillis();
    }
    int retval=-INFINITE;
    int i;
    Te te=new Te(0,0,0,false,0);
    for(i=depth+1;i<=depthMax && !stop ;i++) {
      retval=negaMax(te,k,alpha,beta,depth,i,false);
      if (depth==0 && System.currentTimeMillis()-time>sikoutime) {
        break;
      }
    }
    if (depth==0) {
      if (stop) {
        stop=false;
        stopped=true;
      } else {
        processed=true;
      }
    }
    return retval;
  }
  public Te getNextTe(Kyokumen k,int tesu,int spenttime,int limittime,int byoyomi) {
    leaf=node=0;
    Te te;
    if (limittime-spenttime<120) {
      sikoutime=1800; // 1.8秒
    } else if (limittime-spenttime<180) {
      sikoutime=18000; // 18秒
    } else {
      sikoutime=40000; // 40秒
    }
    if ((te=joseki.fromJoseki(k,tesu))!=null) {
      System.out.println("定跡より:"+te.toString());
      return te;
    }
    if (yosoku!=null && yosoku.equals(k)) {
      // 予測が当たっているので、このまま進める。
      System.out.println("予測あたり");
      while(!processed) {
        try {
          Thread.sleep(100);
        }catch(Exception e){
        }
      }
      te=best[0][0];
    } else {
      if (sikou_ura!=null && !processed) {
        stop=true;
        while(!stopped) {
          try {
            Thread.sleep(100);
          }catch(Exception e){
          }
        }
      }
      kk=new KyokumenKomagumi(k);
      // 評価値最大の手を得る
      // 投了にあたるような手で初期化。
      te=new Te(0,0,0,false,0);
      int v=ITDeep(kk,-INFINITE,INFINITE,0,DEPTH_MAX);
      if (v>-INFINITE) {
        te=best[0][0];
      }
    }
    System.out.print("  最善手順:");
    for(int i=0;i<DEPTH_MAX;i++) {
      System.out.print(best[0][i]);
    }
    System.out.println();
    time=System.currentTimeMillis()-time;
    System.out.println("leaf="+leaf+" node="+node+" time="+time+"ms");
    // 自分の手と、予測した相手の手で先に進める。
    kk.move(best[0][0]);
    kk.move(best[0][1]);
    yosoku=new KyokumenKomagumi((Kyokumen)kk.clone());
    sikou_ura=new Thread(this);
    sikou_ura.start();
    return te;
  }
  // 相手の思考中に考える。
  public void run() {
    processed=false;
    stop=false;
    stopped=false;
    int v=ITDeep(kk,-INFINITE,INFINITE,0,DEPTH_MAX);
    sikou_ura=null;
  }
}
class TTEntry {
  // 定数定義
  // αβ探索で得た値が、局面の評価値そのもの
  static final public int EXACTLY_VALUE=0;
  // αβ探索で得た値が、β以上だった（valueは下限の値）
  static final public int LOWER_BOUND=1;
  // αβ探索で得た値が、α以下だった（valueは上限の値）
  static final public int UPPER_BOUND=2;
  // ハッシュ値
  public int HashVal;
  // 前回の探索での最善手
  public Te best;
  // 前々回以前の探索での最善手
  public Te second;
  // 前回の探索時の評価地
  public int value;
  // その評価値がどのようなものか？
  // 上記の定数に従う。
  public int flag;
  // 評価値を得た際の、手数
  public int tesu;
  // 評価値を得た際の深さ
  public int depth;
  // 評価値を得た際の読みの残り深さ
  public int remainDepth;
}
class Te implements Cloneable,Constants {
  int koma;                 // どの駒が動いたか
  int from;                 // 動く前の位置（持ち駒の場合、0）
  int to;                   // 動いた先の位置
  boolean promote;         // 成る場合、true 成らない場合 false
  int capture;              // 取った駒（Kyokumenのback関数で利用する）
  int value;                // 手の価値
  int value2;               // 手の価値：攻撃点を加味しない価値。
  public Te(int _koma,int _from,int _to,boolean _promote,int _capture) {
    koma=_koma;
    from=_from;
    to=  _to;
    promote=_promote;
    capture=_capture;
    value=0;
  }
  public Te() {
    koma=from=to=capture=0;
    promote=false;
    value=0;
  }
  public boolean equals(Te te) {
    return (te.koma==koma && te.from==from && te.to==to && te.promote==promote);
  }
  public boolean equals(Object _te) {
    Te te=(Te)_te;
    if (te==null) return false;
    return equals(te);
  }
  public Object clone() {
    return new Te(koma,from,to,promote,capture);
  }
  // 手を文字列で表現する。
  public String toString() {
    return sujiStr[to>>4]+danStr[to&0x0f]+
            Koma.toString(koma)+(promote?"成":"")+
            (from==0?"打":"("+sujiStr[from>>4]+danStr[from&0x0f]+")")+
            (promote?"":"");
  }
}
// 局面に与えたハッシュコードで格納するクラス
class TranspositionTable {
  public TTEntry table[]=new TTEntry[0x100000];
  public TTEntry get(int HashVal) {
    if (table[HashVal & 0x0fffff]!=null && 
        table[HashVal & 0x0fffff].HashVal==HashVal) {
      return table[HashVal & 0x0fffff];
    }
    return null;
  }
  public void add(int HashVal,int value,int alpha,int beta,Te best,
      int depth,int remainDepth,int tesu) {
    TTEntry e=get(HashVal);
    if (e==null) {
      e=new TTEntry();
      e.second=null;
    } else {
      e.second=e.best;
    }
    e.best=best;
    e.value=value;
    if (value<=alpha) {
      e.flag=TTEntry.UPPER_BOUND;
    } else if (value>=beta) {
      e.flag=TTEntry.LOWER_BOUND;
    } else {
      e.flag=TTEntry.EXACTLY_VALUE;
    }
    e.depth=depth;
    e.remainDepth=remainDepth;
    e.tesu=tesu;
    table[HashVal & 0x0fffff]=e;
  }
}
