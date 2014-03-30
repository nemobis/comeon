package comeon.ui.preferences.main;

import java.awt.event.ActionEvent;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import comeon.ui.actions.BaseAction;
import comeon.ui.preferences.wikis.WikiListCellRenderer;
import comeon.ui.preferences.wikis.WikiModel;
import comeon.ui.preferences.wikis.WikiSubController;
import comeon.ui.preferences.wikis.WikiSubPanel;

@Singleton
public final class WikisListPanel extends ListPanel<WikiModel> {
  private static final long serialVersionUID = 1L;
  
  private final ActivateAction activateAction;

  @Inject
  public WikisListPanel(final WikiSubController subController,
      final WikiSubPanel subPanel) {
    super(new WikiListCellRenderer(), subController, subPanel, subController.getMainController().getWikis(), "wikis", WikiModel.getPrototype());
    this.activateAction = new ActivateAction();
    list.addListSelectionListener(new ListSelectionListener() {
      @Override
      public void valueChanged(final ListSelectionEvent e) {
        if (!e.getValueIsAdjusting()) {
          final boolean isSomethingSelected = list.getSelectedIndex() >= 0;
          SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
              activateAction.setEnabled(isSomethingSelected);
            }
          });
        }
      }
    });
    list.getModel().addListDataListener(new ListDataListener() {
      @Override
      public void intervalRemoved(final ListDataEvent e) {
        this.updateActivateActionStatus();
      }
      
      @Override
      public void intervalAdded(final ListDataEvent e) {
        this.updateActivateActionStatus();
      }
      
      @Override
      public void contentsChanged(final ListDataEvent e) {
        // Noop
      }

      private void updateActivateActionStatus() {
        SwingUtilities.invokeLater(new Runnable() {
          @Override
          public void run() {
            activateAction.setEnabled(list.getModel().getSize() > 1);
            if (list.getModel().getSize() == 1) {
              subController.getMainController().setActiveWiki(0);
            }
          }
        });
      }
    });
    super.addCustomButton(new JButton(activateAction));
  }
  
  private class ActivateAction extends BaseAction {
    private static final long serialVersionUID = 1L;

    public ActivateAction() {
      super("prefs.wikis.activate");
      this.setEnabled(false);
    }
    
    @Override
    public void actionPerformed(final ActionEvent e) {
      subController.getMainController().setActiveWiki(list.getSelectedIndex());
    }
    
  }
}