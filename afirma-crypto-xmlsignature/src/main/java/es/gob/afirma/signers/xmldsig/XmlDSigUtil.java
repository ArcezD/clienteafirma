/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.signers.xmldsig;

import java.util.ArrayList;
import java.util.List;

import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.Transform;

import es.gob.afirma.signers.xml.Utils;

/** Utilidades espec&iacute;ficas para XMLDSig. */
final class XmlDSigUtil {

	private XmlDSigUtil() {
		// No permitimos la instanciacion
	}

    /** En una lista de referencias, se eliminan las transformaciones Base64 de
     * aquellas que tengan el identificador nulo.
     * @param referenceList Lista de referencias original (no se modifica)
     * @return Nueva lista de referencias */
    static List<Reference> cleanReferencesList(final List<Reference> referenceList) {

        final List<Reference> newList = new ArrayList<>();
        if (referenceList == null) {
            return newList;
        }
        List<Transform> trans;

        boolean needsReconReference;

        for (final Reference r : referenceList) {
            if (r.getId() == null) {
                // Por cada referencia guardamos sus transformaciones que no son
                // Base64 por si hay que
                // reconstruirla
                trans = null;
                needsReconReference = false;
                for (final Object t : r.getTransforms()) {
                    if (t instanceof Transform) {
                        if (!"http://www.w3.org/2000/09/xmldsig#base64".equals(((Transform) t).getAlgorithm())) { //$NON-NLS-1$
                            // Si el ID es nulo y hay una transformacion Base64
                            // reconstruimos la referencia pero quitando esa transformacion Base64
                            if (trans == null) {
                                trans = new ArrayList<>();
                            }
                            trans.add((Transform) t);
                        }
                        else {
                            needsReconReference = true;
                        }
                    }
                }
                // Ya tenemos las referencias, si se necesita reconstruir
                // la reconstruimos y la anadimos
                if (needsReconReference) {
                    newList.add(
                		Utils.getDOMFactory().newReference(
            				r.getURI(),
            				r.getDigestMethod(),
            				trans,
            				r.getType(),
            				r.getId()
        				)
    				);
                }
                // Si no, la referencia es buena y la podemos anadir
                // directamente
                else {
                    newList.add(r);
                }
            }
            else {
                newList.add(r);
            }
        }
        return newList;
    }
}
