package comeon.ui.actions;

import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.ActionEvent;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import comeon.ui.UI;

public final class AboutAction extends BaseAction {

  private static final long serialVersionUID = 1L;

  private final Image icon;

  public AboutAction() {
    super("about");
    icon = UI.ICON_IMAGES.get(UI.ICON_IMAGES.size() - 1);
  }

  @Override
  public void actionPerformed(final ActionEvent e) {
    SwingUtilities.invokeLater(new Runnable() {

      @Override
      public void run() {
        final JTextArea messageArea = new JTextArea(UI.BUNDLE.getString("about.message"));
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        final JScrollPane messageScrollArea = new JScrollPane(messageArea);
        messageScrollArea.setPreferredSize(new Dimension(380, 240));
        JOptionPane.showMessageDialog(JOptionPane.getRootFrame(), new Object[] { new JLabel(UI.BUNDLE.getString("comeon")),
            messageScrollArea }, UI.BUNDLE.getString("action.about.title"), JOptionPane.PLAIN_MESSAGE, new ImageIcon(
            icon));
      }
    });
  }

}
