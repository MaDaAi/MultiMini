package app.multimin.model;

import javax.swing.DefaultListModel;

public final class View {
    public String name;
    public final DefaultListModel<Source> sources = new DefaultListModel<>();
    public int controlSource = 0;

    public View(String name) {
        this.name = name;
    }
}
