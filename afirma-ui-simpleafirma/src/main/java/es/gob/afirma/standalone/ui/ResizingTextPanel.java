/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation; 
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * Date: 11/01/11
 * You may contact the copyright holder at: soporte.afirma5@mpt.es
 */

package es.gob.afirma.standalone.ui;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;

import javax.swing.JPanel;

final class ResizingTextPanel extends JPanel {

    private static final long serialVersionUID = -5496697696047898537L;

    private static final int MARGIN = 30;

    private final String text;
    private Font font;

    private void changeInternalFont() {
        FontMetrics fm = getFontMetrics(this.font);
        while (true) {
            if (getWidth() <= fm.stringWidth(this.text) + (MARGIN * 3)) {
                break;
            }
            this.font = this.font.deriveFont((float) this.font.getSize() + 1);
            fm = getFontMetrics(this.font);
        }
        while (true) {
            if (getWidth() > fm.stringWidth(this.text) + (MARGIN * 3)) {
                break;
            }
            this.font = this.font.deriveFont((float) this.font.getSize() - 1);
            fm = getFontMetrics(this.font);
        }
    }

    ResizingTextPanel(final String txt) {
        super(true);
        this.text = txt;
        this.font = this.getFont();
    }

    @Override
    public void paintComponent(final Graphics g) {
        super.paintComponent(g);
        changeInternalFont();
        g.setFont(this.font);
        g.drawString(this.text, MARGIN, getSize().height / 2);
    }

}
