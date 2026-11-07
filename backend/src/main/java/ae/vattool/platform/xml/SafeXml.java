package ae.vattool.platform.xml;

import ae.vattool.platform.web.DomainException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.util.ArrayList;
import java.util.List;
import org.xml.sax.SAXException;

/**
 * Parses XML from outside the system. Client files are untrusted, so document types and external
 * entities are refused outright: that closes file disclosure and entity-expansion attacks.
 */
public final class SafeXml {

    private SafeXml() {}

    public static Document parse(byte[] content) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(content));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw DomainException.invalid("XML_UNREADABLE", "The XML could not be read: " + e.getMessage());
        }
    }

    /** Direct children with a local name, whatever namespace prefix the sender used. */
    public static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    public static Element child(Element parent, String localName) {
        List<Element> found = children(parent, localName);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** Follows a path of local names from an element, and returns null if any step is absent. */
    public static Element path(Element from, String... names) {
        Element cursor = from;
        for (String name : names) {
            if (cursor == null) {
                return null;
            }
            cursor = child(cursor, name);
        }
        return cursor;
    }

    public static String text(Element from, String... names) {
        Element e = path(from, names);
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }
}
