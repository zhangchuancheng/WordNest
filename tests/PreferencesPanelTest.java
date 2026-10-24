package wordnest;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
public class PreferencesPanelTest {
 static int checks;
 static void check(boolean ok,String why) { checks++; if(!ok) throw new AssertionError(why); }
 static void layout(Container c) { c.doLayout(); for(Component x:c.getComponents()) if(x instanceof Container child) layout(child); }
 public static void main(String[] args) throws Exception {
  SwingUtilities.invokeAndWait(()->{
   Theme.install();
   for(int[] size:new int[][]{{340,220},{500,400},{640,520}}) {
    PreferencesPanel p=new PreferencesPanel(1,0); p.setSize(size[0],size[1]);
    for(int i=0;i<4;i++) layout(p);
    check(p.close.getSelectedIndex()==1 && p.minimize.getSelectedIndex()==0,"Existing settings retained");
    for(JButton b:new JButton[]{p.save,p.cancel,p.about}) {
     Rectangle r=SwingUtilities.convertRectangle(b.getParent(),b.getBounds(),p);
     check(r.y>=0 && r.y+r.height<=size[1]-16,"Button fully inside bottom padding");
     check(r.height>=b.getPreferredSize().height,"Button height fits font");
    }
    if(size[1]==220) check(p.scroll.getVerticalScrollBar().isVisible(),"Short dialog scrolls body");
    check(p.scroll.getViewport().getView().getWidth()==p.scroll.getViewport().getWidth(),"Text body tracks available width");
    if(size[0]==500) {
     BufferedImage im=new BufferedImage(size[0],size[1],BufferedImage.TYPE_INT_RGB);
     Graphics2D g=im.createGraphics();p.printAll(g);g.dispose();
     try {ImageIO.write(im,"png",Path.of("build","preferences-preview.png").toFile());}catch(Exception e){throw new RuntimeException(e);}
    }
   }
   check(Window.getWindows().length==0,"No windows created");
  });
  System.out.println("PASS: "+checks+" settings layout assertions, scale="+System.getProperty("flatlaf.uiScale"));
 }
}
