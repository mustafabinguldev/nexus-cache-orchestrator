package network.darkland;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.time.LocalTime;
import java.util.*;
import java.util.List;

public class Main {

    private static JFrame       frame;
    private static JPanel       mainPanel;
    private static CardLayout   cardLayout;
    private static NexusConsole console;

    private static DonutCard  cpuCard, ramCard, cacheCard, addonCard;
    private static LineChart  cpuChart, ramChart;
    private static JLabel     statusDot, clockLabel, uptimeLabel;
    private static long       bootTime = System.currentTimeMillis();

    static final Color BG          = new Color(5,   7,  14);
    static final Color CARD        = new Color(10,  15,  26);
    static final Color CARD2       = new Color(13,  20,  34);
    static final Color BORDER      = new Color(22,  34,  58);
    static final Color BORDER_LIT  = new Color(38,  58,  95);

    static final Color CYAN        = new Color(  0, 220, 255);
    static final Color BLUE        = new Color( 25, 130, 255);
    static final Color GREEN       = new Color(  0, 240, 120);
    static final Color ORANGE      = new Color(255, 145,   0);
    static final Color RED         = new Color(255,  55,  70);

    static final Color TEXT_HI     = new Color(215, 230, 255);
    static final Color TEXT_MID    = new Color(105, 135, 180);
    static final Color TEXT_LO     = new Color( 45,  62,  95);
    static final Color SCAN        = new Color(255, 255, 255,   3);

    public static void main(String[] args) {
        try {
            System.setProperty("awt.useSystemAAFontSettings", "on");
            System.setProperty("swing.aatext", "true");
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(Main::boot);
    }

    private static void boot() {
        frame = new JFrame("NEXUS  //  v1.0");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1280, 800);
        frame.setMinimumSize(new Dimension(960, 640));
        frame.setLocationRelativeTo(null);

        StarField bg = new StarField();
        frame.setContentPane(bg);
        bg.setLayout(new BorderLayout());

        cardLayout = new CardLayout();
        mainPanel  = new JPanel(cardLayout);
        mainPanel.setOpaque(false);
        mainPanel.add(loginPanel(),     "LOGIN");
        mainPanel.add(dashboardPanel(), "DASH");

        bg.add(mainPanel, BorderLayout.CENTER);
        cardLayout.show(mainPanel, "LOGIN");
        frame.setVisible(true);
    }

    private static JPanel loginPanel() {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setOpaque(false);

        JPanel card = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = rh(g);
                int w = getWidth(), h = getHeight();
                GradientPaint gp = new GradientPaint(0, 0, CARD, 0, h, CARD2);
                g2.setPaint(gp); g2.fillRoundRect(0, 0, w, h, 20, 20);
                g2.setColor(new Color(255,255,255,9)); g2.fillRoundRect(1,1,w-2,h/3,20,20);
                for (int y=0; y<h; y+=4) { g2.setColor(SCAN); g2.fillRect(1,y,w-2,1); }
                g2.setStroke(new BasicStroke(1.5f));
                g2.setColor(new Color(CYAN.getRed(),CYAN.getGreen(),CYAN.getBlue(),50));
                g2.drawRoundRect(1,1,w-3,h-3,20,20);
                g2.setColor(BORDER); g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0,0,w-1,h-1,20,20);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setPreferredSize(new Dimension(450, 500));
        card.setBorder(new EmptyBorder(44, 50, 44, 50));

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.weightx = 1;

        LogoPanel logo = new LogoPanel();
        logo.setPreferredSize(new Dimension(350, 80));
        c.gridy=0; c.insets=new Insets(0,0,4,0); card.add(logo, c);

        JLabel sub = lbl("INDUSTRIAL CONTROL  //  v4.0", 10, TEXT_LO, false);
        sub.setHorizontalAlignment(SwingConstants.CENTER);
        c.gridy=1; c.insets=new Insets(0,0,28,0); card.add(sub, c);

        c.gridy=2; c.insets=new Insets(0,0,22,0); card.add(new FadeLine(CYAN), c);

        c.insets=new Insets(7,0,7,0);
        NField rField = new NField("127.0.0.1",               "REDIS HOST",  CYAN);
        NField mField = new NField("mongodb://localhost:27017","MONGO URI",   BLUE);
        NField pField = new NField("8080",                    "SERVER PORT", GREEN);
        c.gridy=3; card.add(rField, c);
        c.gridy=4; card.add(mField, c);
        c.gridy=5; card.add(pField, c);

        NBtn btn = new NBtn("INITIALIZE NEXUS ENGINE", CYAN);
        btn.setPreferredSize(new Dimension(0, 52));
        c.gridy=6; c.insets=new Insets(26,0,0,0); card.add(btn, c);

        JLabel footer = lbl("AUTHORIZED ACCESS ONLY  —  NEXUS SYSTEMS", 9, TEXT_LO, false);
        footer.setHorizontalAlignment(SwingConstants.CENTER);
        c.gridy=7; c.insets=new Insets(18,0,0,0); card.add(footer, c);

        btn.addActionListener(e -> {
            btn.setEnabled(false); btn.label = "CONNECTING..."; btn.repaint();
            new Thread(() -> {
                try {
                    redirectOut();
                    new NexusApplication(rField.val(), mField.val());
                    SwingUtilities.invokeLater(() -> {
                        cardLayout.show(mainPanel, "DASH");
                        startTimers(); goOnline();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btn.setEnabled(true);
                        btn.label = "INITIALIZE NEXUS ENGINE"; btn.repaint();
                        JOptionPane.showMessageDialog(frame, "ENGINE ERROR: " + ex.getMessage());
                    });
                }
            }).start();
        });

        outer.add(card);
        return outer;
    }

    private static JPanel dashboardPanel() {
        JPanel root = new JPanel(new BorderLayout(0,0));
        root.setOpaque(false);
        root.add(topBar(),    BorderLayout.NORTH);
        root.add(dashBody(),  BorderLayout.CENTER);
        return root;
    }

    private static JPanel topBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = rh(g);
                g2.setColor(new Color(6,9,18,238));
                g2.fillRect(0,0,getWidth(),getHeight());
                g2.setColor(BORDER_LIT);
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(0,getHeight()-1,getWidth(),getHeight()-1);
                g2.dispose();
            }
        };
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(0,50));
        bar.setBorder(new EmptyBorder(0,24,0,24));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT,14,0));
        left.setOpaque(false);
        left.add(lbl("⬡",18,CYAN,true));
        left.add(lbl("NEXUS",14,TEXT_HI,true));
        left.add(lbl("//",12,TEXT_LO,false));
        left.add(lbl("LIVE DASHBOARD",10,TEXT_LO,false));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT,20,0));
        right.setOpaque(false);
        uptimeLabel = lbl("UP 00:00:00",10,TEXT_LO,false);
        statusDot   = lbl("● OFFLINE",10,RED,true);
        clockLabel  = lbl("00:00:00",13,CYAN,true);
        right.add(uptimeLabel);
        right.add(lbl("|",12,TEXT_LO,false));
        right.add(statusDot);
        right.add(lbl("|",12,TEXT_LO,false));
        right.add(clockLabel);

        bar.add(left,  BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private static JPanel dashBody() {
        JPanel body = new JPanel(new BorderLayout(0,14));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(18,22,18,22));

        JPanel donuts = new JPanel(new GridLayout(1,4,14,0));
        donuts.setOpaque(false);
        cpuCard   = new DonutCard("CPU LOAD",      CYAN,   new Color(0,44,70));
        ramCard   = new DonutCard("PROCESS RAM",   BLUE,   new Color(8,26,70));
        cacheCard = new DonutCard("CACHE OBJECTS", GREEN,  new Color(0,44,22));
        addonCard = new DonutCard("ACTIVE ADDONS", ORANGE, new Color(55,26,0));
        donuts.add(cpuCard); donuts.add(ramCard);
        donuts.add(cacheCard); donuts.add(addonCard);

        JPanel charts = new JPanel(new GridLayout(1,2,14,0));
        charts.setOpaque(false);
        cpuChart = new LineChart("CPU HISTORY — 60s", CYAN, new Color(0,220,255,28));
        ramChart = new LineChart("RAM HISTORY — 60s", BLUE, new Color(25,130,255,28));
        charts.add(cpuChart); charts.add(ramChart);

        JPanel topSection = new JPanel(new GridLayout(2,1,0,14));
        topSection.setOpaque(false);
        topSection.add(donuts);
        topSection.add(charts);

        console = new NexusConsole();
        JScrollPane scroll = styledScroll(console);
        JPanel wrap = consoleWrapper(scroll);

        body.add(topSection, BorderLayout.NORTH);
        body.add(wrap,       BorderLayout.CENTER);
        return body;
    }

    private static JPanel consoleWrapper(JScrollPane scroll) {
        JPanel wrap = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = rh(g);
                int w=getWidth(), h=getHeight();
                g2.setColor(CARD); g2.fillRoundRect(0,0,w,h,12,12);
                g2.setStroke(new BasicStroke(1f)); g2.setColor(BORDER);
                g2.drawRoundRect(0,0,w-1,h-1,12,12);
                g2.setColor(new Color(CYAN.getRed(),CYAN.getGreen(),CYAN.getBlue(),32));
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawRoundRect(1,1,w-3,h-3,12,12);
                g2.dispose();
            }
        };
        wrap.setOpaque(false);

        JPanel hdr = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = rh(g);
                g2.setColor(new Color(7,11,21));
                g2.fillRoundRect(0,0,getWidth(),getHeight()+14,12,12);
                g2.setColor(BORDER_LIT); g2.setStroke(new BasicStroke(1f));
                g2.drawLine(0,getHeight()-1,getWidth(),getHeight()-1);
                g2.dispose();
            }
        };
        hdr.setOpaque(false);
        hdr.setBorder(new EmptyBorder(8,14,8,14));
        hdr.setPreferredSize(new Dimension(0,36));

        JPanel lights = new JPanel(new FlowLayout(FlowLayout.LEFT,7,0));
        lights.setOpaque(false);
        for (Color c : new Color[]{RED,ORANGE,GREEN})
            lights.add(lbl("●",11,c,false));

        hdr.add(lights, BorderLayout.WEST);
        hdr.add(lbl("  SYSTEM LOG  //  NEXUS OUTPUT STREAM",10,TEXT_MID,true), BorderLayout.CENTER);
        hdr.add(lbl("LIVE",9,new Color(GREEN.getRed(),GREEN.getGreen(),GREEN.getBlue(),140),false), BorderLayout.EAST);

        wrap.add(hdr,    BorderLayout.NORTH);
        wrap.add(scroll, BorderLayout.CENTER);
        return wrap;
    }

    private static void startTimers() {
        new Timer(1000, e -> {
            LocalTime t = LocalTime.now();
            clockLabel.setText(String.format("%02d:%02d:%02d",t.getHour(),t.getMinute(),t.getSecond()));
            long up = (System.currentTimeMillis()-bootTime)/1000;
            uptimeLabel.setText(String.format("UP %02d:%02d:%02d",up/3600,(up%3600)/60,up%60));
        }).start();

        new Thread(() -> {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean)
                            ManagementFactory.getOperatingSystemMXBean();

            Runtime rt = Runtime.getRuntime();

            try { Thread.sleep(500); } catch (InterruptedException x) { return; }

            while (true) {
                double cpu = osBean.getProcessCpuLoad() * 100;
                if (cpu < 0) cpu = 0;

                long mb  = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
                int  rp  = (int)(mb * 100 / Math.max(1, rt.maxMemory() / 1024 / 1024));
                int  ca  = NexusApplication.getApplication().getDataSize();
                int  ad  = NexusApplication.getApplication().getAddonSize();

                final double fc = cpu;
                final long   fm = mb;
                final int    fr = rp;

                SwingUtilities.invokeLater(() -> {
                    cpuCard  .set((int)fc, String.format("%.1f%%", fc));
                    ramCard  .set(fr,      fm + " MB");
                    cacheCard.set(Math.min(ca, 100), String.valueOf(ca));
                    addonCard.set(Math.min(ad * 10, 100), String.valueOf(ad));
                    cpuChart.push((int)fc);
                    ramChart.push(fr);
                });

                try { Thread.sleep(1000); } catch (InterruptedException x) { break; }
            }
        }).start();
    }

    private static void goOnline() {
        statusDot.setText("● ONLINE"); statusDot.setForeground(GREEN);
        bootTime = System.currentTimeMillis();
    }

    static class DonutCard extends JPanel {
        private final String title;
        private final Color  accent, track;
        private float arc = 0f;
        private int   pct = 0;
        private String val = "—";
        private float  gp  = 0f;
        private Timer  anim, pulse;

        DonutCard(String title, Color accent, Color track) {
            this.title=title; this.accent=accent; this.track=track;
            setOpaque(false);
            setPreferredSize(new Dimension(0,200));
            pulse = new Timer(40, e -> { gp+=0.055f; repaint(); });
            pulse.start();
        }

        void set(int p, String v) {
            pct=p; val=v;
            if (anim!=null) anim.stop();
            float target=p*3.6f, step=(target-arc)/14f;
            anim=new Timer(16,null);
            anim.addActionListener(e -> {
                arc+=step;
                if (Math.abs(arc-target)<0.6f) { arc=target; anim.stop(); }
                repaint();
            });
            anim.start();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = rh(g);
            int w=getWidth(), h=getHeight(), cx=w/2, cy=h/2+6;

            cardBg(g2,w,h,12);

            float glow = (float)(0.5+0.5*Math.sin(gp));

            int ba = pct>85 ? (int)(110+glow*145) : (int)(28+glow*55);
            Color bc = pct>85 ? blend(accent,RED,0.65f)
                    : new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),ba);
            g2.setStroke(new BasicStroke(1.4f)); g2.setColor(bc);
            g2.drawRoundRect(1,1,w-3,h-3,12,12);

            g2.setFont(new Font("Consolas",Font.BOLD,10)); g2.setColor(TEXT_LO);
            cstr(g2,title,cx,17);

            int R=Math.max(Math.min(w-40,h-46),70);
            int T=14;

            for (int i=0; i<40; i++) {
                double rad=Math.toRadians(-90+i*9.0);
                int r1=R/2+5, r2=R/2+(i%5==0?12:8);
                g2.setStroke(new BasicStroke(i%5==0?1.3f:0.7f));
                g2.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),i%5==0?52:20));
                g2.drawLine((int)(cx+r1*Math.cos(rad)),(int)(cy+r1*Math.sin(rad)),
                        (int)(cx+r2*Math.cos(rad)),(int)(cy+r2*Math.sin(rad)));
            }

            g2.setStroke(new BasicStroke(T,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(track.getRed(),track.getGreen(),track.getBlue(),85));
            g2.drawOval(cx-R/2,cy-R/2,R,R);

            if (arc>1.5f) {
                for (int l=9; l>=1; l--) {
                    int a=(int)(glow*30*(9-l)/9f);
                    g2.setStroke(new BasicStroke(T+l*2.1f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                    g2.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),a));
                    g2.drawArc(cx-R/2,cy-R/2,R,R,90,-(int)arc);
                }
                int segs=Math.max(1,(int)(arc/4));
                for (int i=0; i<segs; i++) {
                    float t=(float)i/segs;
                    g2.setColor(blend(accent.darker().darker(),accent,t));
                    g2.setStroke(new BasicStroke(T,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND));
                    g2.drawArc(cx-R/2,cy-R/2,R,R, 90-(int)(t*arc), -(Math.max(2,(int)(arc/segs))+1));
                }
                if (arc>8) {
                    double rad=Math.toRadians(90-arc);
                    int tx=(int)(cx+(R/2.0)*Math.cos(rad)), ty=(int)(cy-(R/2.0)*Math.sin(rad));
                    for (int r=12; r>=4; r--) {
                        g2.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),(int)(glow*88*(12-r)/12f)));
                        g2.fillOval(tx-r,ty-r,r*2,r*2);
                    }
                    g2.setColor(Color.WHITE); g2.fillOval(tx-4,ty-4,8,8);
                }
            }

            g2.setStroke(new BasicStroke(1.5f));
            g2.setColor(new Color(0,0,0,65));
            g2.drawOval(cx-R/2+T,cy-R/2+T,R-T*2,R-T*2);

            g2.setFont(new Font("Consolas",Font.BOLD,22));
            g2.setColor(TEXT_HI); cstr(g2,val,cx,cy+8);

            g2.setFont(new Font("Consolas",Font.PLAIN,9));
            g2.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),145));
            cstr(g2,pct+"% UTIL",cx,cy+22);

            g2.dispose();
        }
    }

    static class LineChart extends JPanel {
        private final String title;
        private final Color  lineC, fillC;
        private final Deque<Integer> hist = new ArrayDeque<>();
        private static final int N = 60;
        private int  cur = 0;
        private float dp = 0f;
        private Timer dt;

        LineChart(String title, Color lineC, Color fillC) {
            this.title=title; this.lineC=lineC; this.fillC=fillC;
            setOpaque(false);
            for (int i=0; i<N; i++) hist.add(0);
            dt=new Timer(40, e -> { dp+=0.10f; repaint(); });
            dt.start();
        }

        void push(int v) { cur=v; hist.pollFirst(); hist.addLast(v); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = rh(g);
            int w=getWidth(), h=getHeight();
            int px=12, top=34, bot=20;
            int cH=h-top-bot, cW=w-px*2;

            cardBg(g2,w,h,12);

            g2.setFont(new Font("Consolas",Font.BOLD,10)); g2.setColor(TEXT_LO);
            g2.drawString(title,px+4,22);

            String cs=cur+"%";
            g2.setFont(new Font("Consolas",Font.BOLD,17)); g2.setColor(lineC);
            g2.drawString(cs,w-g2.getFontMetrics().stringWidth(cs)-px-4,22);

            g2.setStroke(new BasicStroke(0.5f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_BEVEL,
                    0,new float[]{3,5},0));
            for (int pct : new int[]{25,50,75,100}) {
                int y=top+cH-(int)(cH*pct/100.0);
                g2.setColor(new Color(255,255,255,10)); g2.drawLine(px,y,w-px,y);
                g2.setFont(new Font("Consolas",Font.PLAIN,8)); g2.setColor(TEXT_LO);
                g2.drawString(pct+"%",px+2,y-3);
            }

            List<Integer> vals=new ArrayList<>(hist);
            int n=vals.size(); if (n<2) { g2.dispose(); return; }
            float sx=(float)cW/(n-1);
            int[] xs=new int[n], ys=new int[n];
            for (int i=0; i<n; i++) {
                xs[i]=px+(int)(i*sx);
                ys[i]=top+cH-(int)(cH*Math.min(100,vals.get(i))/100.0);
            }

            Path2D path=new Path2D.Float();
            path.moveTo(xs[0],top+cH);
            for (int i=0; i<n; i++) path.lineTo(xs[i],ys[i]);
            path.lineTo(xs[n-1],top+cH); path.closePath();
            g2.setPaint(new GradientPaint(0,top,fillC,0,top+cH,new Color(0,0,0,0)));
            g2.fill(path);

            for (int pass=5; pass>=1; pass--) {
                g2.setColor(new Color(lineC.getRed(),lineC.getGreen(),lineC.getBlue(),(int)(34.0/pass)));
                g2.setStroke(new BasicStroke(pass*2.2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                for (int i=0; i<n-1; i++) g2.drawLine(xs[i],ys[i],xs[i+1],ys[i+1]);
            }
            g2.setColor(lineC);
            g2.setStroke(new BasicStroke(1.6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            for (int i=0; i<n-1; i++) g2.drawLine(xs[i],ys[i],xs[i+1],ys[i+1]);

            int lx=xs[n-1], ly=ys[n-1];
            float tw=(float)(0.5+0.5*Math.sin(dp));
            for (int r=10; r>=3; r--) {
                g2.setColor(new Color(lineC.getRed(),lineC.getGreen(),lineC.getBlue(),(int)(tw*68*(10-r)/10f)));
                g2.fillOval(lx-r,ly-r,r*2,r*2);
            }
            g2.setColor(Color.WHITE); g2.fillOval(lx-3,ly-3,6,6);

            g2.setFont(new Font("Consolas",Font.PLAIN,8)); g2.setColor(TEXT_LO);
            g2.drawString("60s",  px+2,    h-5);
            g2.drawString("30s",  px+cW/2, h-5);
            g2.drawString("NOW",  w-px-22, h-5);

            g2.setStroke(new BasicStroke(1f)); g2.setColor(BORDER);
            g2.drawRoundRect(0,0,w-1,h-1,12,12);

            g2.dispose();
        }
    }

    static class NexusConsole extends JTextPane {
        private final StyledDocument doc;
        private int lineNum = 0;

        NexusConsole() {
            setOpaque(true);
            setBackground(new Color(3,5,11));
            setEditable(false);
            setBorder(new EmptyBorder(10,6,10,10));
            doc = getStyledDocument();
            appendLine("[BOOT]  Nexus v1.0 starting up...", "SYS");
            appendLine("[BOOT]  Loading kernel modules...",           "SYS");
            appendLine("[OK]    NetworkStack module ready",           "OK");
            appendLine("[OK]    CacheEngine module ready",            "OK");
            appendLine("[BOOT]  Awaiting engine initialization...",   "SYS");
        }

        void appendLine(String msg, String type) {
            lineNum++;
            LocalTime t = LocalTime.now();
            String ts = String.format("%02d:%02d:%02d.%03d",
                    t.getHour(),t.getMinute(),t.getSecond(),t.getNano()/1_000_000);
            try {
                doc.insertString(doc.getLength(),
                        String.format("%5d  ",lineNum), sty(TEXT_LO,11));
                doc.insertString(doc.getLength(),
                        "["+ts+"] ", sty(new Color(28,50,95),11));
                doc.insertString(doc.getLength(),
                        promptGlyph(type)+" ", sty(promptColor(type),12));
                doc.insertString(doc.getLength(),
                        msg+"\n", sty(msgColor(type,msg),13));
            } catch (BadLocationException ignored) {}
            setCaretPosition(doc.getLength());
        }

        private String promptGlyph(String t) {
            return switch(t) {
                case "OK"   -> "✓";
                case "ERR"  -> "✗";
                case "WARN" -> "!";
                case "SYS"  -> "⬡";
                default     -> "›";
            };
        }
        private Color promptColor(String t) {
            return switch(t) {
                case "OK"   -> GREEN;
                case "ERR"  -> RED;
                case "WARN" -> ORANGE;
                case "SYS"  -> CYAN;
                default     -> TEXT_MID;
            };
        }
        private Color msgColor(String type, String msg) {
            String lo=msg.toLowerCase();
            if (type.equals("ERR")||lo.contains("error")||lo.contains("fail")||lo.contains("exception"))
                return new Color(255,100,100);
            if (type.equals("WARN")||lo.contains("warn"))
                return ORANGE;
            if (type.equals("OK")||lo.contains("[ok]")||lo.contains("success")||lo.contains("connected"))
                return GREEN;
            if (type.equals("SYS")||lo.contains("[boot]")||lo.contains("init")||lo.contains("start"))
                return CYAN;
            if (lo.contains("debug")||lo.contains("trace"))
                return TEXT_LO;
            return new Color(162,192,255);
        }
        private AttributeSet sty(Color c, int sz) {
            SimpleAttributeSet s=new SimpleAttributeSet();
            StyleConstants.setFontFamily(s,"Consolas");
            StyleConstants.setFontSize(s,sz);
            StyleConstants.setForeground(s,c);
            return s;
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2=(Graphics2D)g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.055f));
            for (int y=0; y<getHeight(); y+=3) {
                g2.setColor(Color.BLACK); g2.fillRect(0,y,getWidth(),1);
            }
            g2.dispose();
        }
    }

    static class LogoPanel extends JPanel {
        private float phase=0f;
        LogoPanel() {
            setOpaque(false);
            new Timer(40, e -> { phase+=0.05f; repaint(); }).start();
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2=rh(g);
            int w=getWidth(), h=getHeight();
            float glow=(float)(0.5+0.5*Math.sin(phase));
            g2.setFont(new Font("Consolas",Font.BOLD,38));
            FontMetrics fm=g2.getFontMetrics();
            int tx=(w-fm.stringWidth("NEXUS CORE"))/2;
            for (int r=16; r>=1; r--) {
                int a=(int)(glow*4.5*(16-r)/16f);
                g2.setColor(new Color(CYAN.getRed(),CYAN.getGreen(),CYAN.getBlue(),a));
                g2.setFont(new Font("Consolas",Font.BOLD,38+r/5));
                g2.drawString("NEXUS CORE",tx-(r/6),h-16+(r/10));
            }
            g2.setFont(new Font("Consolas",Font.BOLD,38));
            GradientPaint gp=new GradientPaint(0,0,CYAN,w,0,BLUE);
            g2.setPaint(gp); g2.drawString("NEXUS CORE",tx,h-16);
            g2.setPaint(new GradientPaint(tx,h-7,
                    new Color(CYAN.getRed(),CYAN.getGreen(),CYAN.getBlue(),(int)(45+glow*85)),
                    tx+fm.stringWidth("NEXUS CORE"),h-7,
                    new Color(BLUE.getRed(),BLUE.getGreen(),BLUE.getBlue(),(int)(45+glow*85))));
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(tx,h-7,tx+fm.stringWidth("NEXUS CORE"),h-7);
            g2.dispose();
        }
    }

    static class StarField extends JPanel {
        private final List<float[]> stars=new ArrayList<>();
        private final Random rng=new Random();
        StarField() {
            setBackground(BG);
            for (int i=0; i<180; i++) stars.add(new float[]{
                    rng.nextFloat()*1280, rng.nextFloat()*860,
                    0.06f+rng.nextFloat()*0.28f,
                    0.4f+rng.nextFloat()*1.6f,
                    rng.nextFloat()*6.28f
            });
            new Timer(33, e -> { for (float[] s:stars) s[4]+=s[2]*0.06f; repaint(); }).start();
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2=rh(g);
            int w=getWidth(), h=getHeight();
            g2.setColor(BG); g2.fillRect(0,0,w,h);
            g2.setPaint(new RadialGradientPaint(new Point(w/2,h/2),Math.max(w,h)*0.55f,
                    new float[]{0f,1f},
                    new Color[]{new Color(10,18,38,55),new Color(0,0,0,0)}));
            g2.fillRect(0,0,w,h);
            for (float[] s:stars) {
                float tw=(float)Math.abs(Math.sin(s[4]));
                int a=(int)(25+tw*205);
                float sz=s[3]*(0.4f+tw*0.6f);
                g2.setColor(new Color(175,200,255,a));
                if (sz>1.3f) {
                    g2.fill(new Ellipse2D.Float(s[0]-sz/2,s[1]-sz/2,sz,sz));
                    if (sz>1.6f && tw>0.75f) {
                        g2.setColor(new Color(200,225,255,(int)(tw*48)));
                        g2.drawLine((int)s[0]-4,(int)s[1],(int)s[0]+4,(int)s[1]);
                        g2.drawLine((int)s[0],(int)s[1]-4,(int)s[0],(int)s[1]+4);
                    }
                } else g2.fillRect((int)s[0],(int)s[1],1,1);
            }
            g2.setPaint(new RadialGradientPaint(new Point(w/2,h/2),Math.max(w,h)*0.72f,
                    new float[]{0f,1f},
                    new Color[]{new Color(0,0,0,0),new Color(0,0,0,170)}));
            g2.fillRect(0,0,w,h);
            g2.dispose();
        }
    }

    static class NBtn extends JButton {
        String label;
        private final Color ac;
        private float hov=0f, pr=0f;
        private Timer ha;
        NBtn(String label, Color ac) {
            super(label); this.label=label; this.ac=ac;
            setOpaque(false); setContentAreaFilled(false);
            setBorderPainted(false); setFocusPainted(false);
            setFont(new Font("Consolas",Font.BOLD,13));
            setForeground(Color.WHITE);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter(){
                public void mouseEntered(MouseEvent e){anim(true);}
                public void mouseExited(MouseEvent e){anim(false);}
                public void mousePressed(MouseEvent e){pr=1f;repaint();}
                public void mouseReleased(MouseEvent e){pr=0f;repaint();}
            });
        }
        private void anim(boolean in){
            if(ha!=null)ha.stop();
            ha=new Timer(16,null);
            ha.addActionListener(e->{
                hov+=in?0.09f:-0.09f; hov=Math.max(0,Math.min(1,hov)); repaint();
                if((in&&hov>=1f)||(!in&&hov<=0f))ha.stop();
            }); ha.start();
        }
        @Override protected void paintComponent(Graphics g){
            Graphics2D g2=rh(g); int w=getWidth(),h=getHeight();
            float sc=1f-pr*0.015f;
            g2.translate(w/2,h/2); g2.scale(sc,sc); g2.translate(-w/2,-h/2);
            for(int i=9;i>=1;i--){
                g2.setColor(new Color(ac.getRed(),ac.getGreen(),ac.getBlue(),(int)(hov*17*(9-i)/9f)));
                g2.setStroke(new BasicStroke(i*2.4f)); g2.drawRoundRect(i,i,w-i*2,h-i*2,10,10);
            }
            Color fill=blend(new Color(11,18,34),ac,hov*0.22f);
            g2.setPaint(new GradientPaint(0,0,fill.brighter(),0,h,fill));
            g2.fillRoundRect(0,0,w,h,10,10);
            g2.setColor(new Color(255,255,255,(int)(11+hov*17)));
            g2.fillRoundRect(1,1,w-2,h/2-1,10,10);
            g2.setStroke(new BasicStroke(1.5f));
            g2.setColor(new Color(ac.getRed(),ac.getGreen(),ac.getBlue(),(int)(72+hov*183)));
            g2.drawRoundRect(1,1,w-3,h-3,10,10);
            g2.setFont(getFont()); g2.setColor(Color.WHITE);
            FontMetrics fm=g2.getFontMetrics();
            g2.drawString(label,(w-fm.stringWidth(label))/2,(h+fm.getAscent()-fm.getDescent())/2);
            g2.dispose();
        }
    }

    static class NField extends JPanel {
        private final JTextField tf;
        private final Color ac;
        private boolean foc=false;
        NField(String val, String label, Color ac) {
            this.ac=ac; setOpaque(false); setLayout(new BorderLayout(0,5));
            add(lbl(label,9,TEXT_LO,true), BorderLayout.NORTH);
            tf=new JTextField(val);
            tf.setOpaque(false); tf.setBackground(new Color(0,0,0,0));
            tf.setForeground(TEXT_HI); tf.setCaretColor(ac);
            tf.setFont(new Font("Consolas",Font.PLAIN,13));
            tf.setBorder(new EmptyBorder(9,12,9,12));
            tf.addFocusListener(new FocusAdapter(){
                public void focusGained(FocusEvent e){foc=true;repaint();}
                public void focusLost(FocusEvent e){foc=false;repaint();}
            });
            add(tf, BorderLayout.CENTER);
        }
        String val(){return tf.getText();}
        @Override protected void paintComponent(Graphics g){
            Graphics2D g2=rh(g); int w=getWidth(),h=getHeight();
            int fy=h-tf.getHeight();
            g2.setColor(new Color(4,8,18)); g2.fillRoundRect(0,fy,w,tf.getHeight(),8,8);
            if(foc){
                g2.setColor(new Color(ac.getRed(),ac.getGreen(),ac.getBlue(),26));
                g2.setStroke(new BasicStroke(3f)); g2.drawRoundRect(0,fy,w-1,tf.getHeight()-1,8,8);
            }
            g2.setColor(foc?ac:BORDER_LIT); g2.setStroke(new BasicStroke(foc?1.5f:1f));
            g2.drawRoundRect(0,fy,w-1,tf.getHeight()-1,8,8);
            g2.dispose();
        }
    }

    static class FadeLine extends JPanel {
        private final Color c;
        FadeLine(Color c){this.c=c; setOpaque(false); setPreferredSize(new Dimension(0,10));}
        @Override protected void paintComponent(Graphics g){
            Graphics2D g2=rh(g); int w=getWidth(),y=getHeight()/2;
            g2.setStroke(new BasicStroke(1f));
            g2.setPaint(new GradientPaint(0,y,new Color(c.getRed(),c.getGreen(),c.getBlue(),0),
                    w/2,y,new Color(c.getRed(),c.getGreen(),c.getBlue(),125)));
            g2.drawLine(0,y,w/2,y);
            g2.setPaint(new GradientPaint(w/2,y,new Color(c.getRed(),c.getGreen(),c.getBlue(),125),
                    w,y,new Color(c.getRed(),c.getGreen(),c.getBlue(),0)));
            g2.drawLine(w/2,y,w,y);
            g2.dispose();
        }
    }

    private static void redirectOut() {
        PrintStream ps=new PrintStream(new OutputStream(){
            private final StringBuilder buf=new StringBuilder();
            @Override public void write(int b){
                if(b=='\n'){
                    final String ln=buf.toString(); buf.setLength(0);
                    SwingUtilities.invokeLater(()->{
                        if(console!=null && !ln.isBlank())
                            console.appendLine(ln,"INFO");
                    });
                } else buf.append((char)b);
            }
        });
        System.setOut(ps); System.setErr(ps);
    }

    static Graphics2D rh(Graphics g){
        Graphics2D g2=(Graphics2D)g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);
        return g2;
    }
    static void cardBg(Graphics2D g2, int w, int h, int arc){
        g2.setPaint(new GradientPaint(0,0,CARD,0,h,CARD2));
        g2.fillRoundRect(0,0,w,h,arc,arc);
        g2.setColor(new Color(255,255,255,8)); g2.fillRoundRect(1,1,w-2,h/3,arc,arc);
        for(int y=0;y<h;y+=4){g2.setColor(SCAN);g2.fillRect(1,y,w-2,1);}
    }
    static void cstr(Graphics2D g2, String s, int cx, int y){
        FontMetrics fm=g2.getFontMetrics();
        g2.drawString(s,cx-fm.stringWidth(s)/2,y);
    }
    static Color blend(Color a, Color b, float t){
        t=Math.max(0,Math.min(1,t));
        return new Color(
                (int)(a.getRed()*(1-t)+b.getRed()*t),
                (int)(a.getGreen()*(1-t)+b.getGreen()*t),
                (int)(a.getBlue()*(1-t)+b.getBlue()*t));
    }
    static JLabel lbl(String t, int sz, Color c, boolean bold){
        JLabel l=new JLabel(t);
        l.setFont(new Font("Consolas",bold?Font.BOLD:Font.PLAIN,sz));
        l.setForeground(c); return l;
    }
    static JScrollPane styledScroll(JComponent c){
        JScrollPane s=new JScrollPane(c);
        s.setBorder(null); s.getViewport().setBackground(new Color(3,5,11));
        s.getVerticalScrollBar().setUnitIncrement(20);
        s.getVerticalScrollBar().setPreferredSize(new Dimension(7,0));
        s.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI(){
            protected void configureScrollBarColors(){thumbColor=new Color(26,52,96);trackColor=new Color(5,8,16);}
            protected JButton createDecreaseButton(int o){return z();}
            protected JButton createIncreaseButton(int o){return z();}
            JButton z(){JButton b=new JButton();b.setPreferredSize(new Dimension(0,0));return b;}
        });
        return s;
    }
}