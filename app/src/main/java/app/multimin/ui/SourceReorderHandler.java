package app.multimin.ui;

import app.multimin.model.Source;
import javax.swing.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;

public final class SourceReorderHandler extends TransferHandler {
    private static final DataFlavor INDEX_FLAVOR = new DataFlavor(Integer.class, "Source index");

    @Override public int getSourceActions(JComponent component) { return MOVE; }

    @Override protected Transferable createTransferable(JComponent component) {
        int index = ((JList<?>) component).getSelectedIndex();
        return new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{INDEX_FLAVOR}; }
            @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return INDEX_FLAVOR.equals(flavor); }
            @Override public Object getTransferData(DataFlavor flavor) { return index; }
        };
    }

    @Override public boolean canImport(TransferSupport support) {
        return support.isDrop() && support.isDataFlavorSupported(INDEX_FLAVOR);
    }

    @Override public boolean importData(TransferSupport support) {
        if (!canImport(support)) return false;
        try {
            @SuppressWarnings("unchecked") JList<Source> list = (JList<Source>) support.getComponent();
            DefaultListModel<Source> model = (DefaultListModel<Source>) list.getModel();
            int from = (Integer) support.getTransferable().getTransferData(INDEX_FLAVOR);
            int to = ((JList.DropLocation) support.getDropLocation()).getIndex();
            if (from < 0 || from >= model.size()) return false;
            Source moved = model.remove(from);
            if (to > from) to--;
            to = Math.max(0, Math.min(to, model.size()));
            model.add(to, moved);
            list.setSelectedIndex(to);
            return true;
        } catch (Exception ignored) { return false; }
    }
}
