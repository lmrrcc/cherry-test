package com.cherrytest.cli.adapters;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

final class XmlNodes {
    private XmlNodes() {}

    static Element parse(String content) {
        rejectDtd(content);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            factory.setExpandEntityReferences(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            Document document = builder.parse(new InputSource(new StringReader(content)));
            return document.getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            String message = exception.getMessage();
            throw new AnyhowError(message == null ? "invalid XML" : message);
        }
    }

    private static void rejectDtd(String content) {
        int index = 0;
        while (index < content.length()) {
            char current = content.charAt(index);
            if (current == ' ' || current == '\t' || current == '\r' || current == '\n') {
                index++;
            } else if (content.startsWith("<?", index)) {
                int end = content.indexOf("?>", index);
                if (end < 0) {
                    return;
                }
                index = end + 2;
            } else if (content.startsWith("<!--", index)) {
                int end = content.indexOf("-->", index);
                if (end < 0) {
                    return;
                }
                index = end + 3;
            } else if (content.startsWith("<!DOCTYPE", index)) {
                throw new AnyhowError("XML with DTD detected");
            } else {
                return;
            }
        }
    }

    static String localName(Node node) {
        String local = node.getLocalName();
        return local == null ? node.getNodeName() : local;
    }

    static boolean hasTagName(Node node, String name) {
        return node.getNodeType() == Node.ELEMENT_NODE && localName(node).equals(name);
    }

    static @Nullable String attribute(Node node, String name) {
        if (!(node instanceof Element element)) {
            return null;
        }
        if (!element.hasAttributeNS(null, name)) {
            return null;
        }
        return element.getAttributeNS(null, name);
    }

    static List<Element> descendants(Node root, String name) {
        List<Element> out = new ArrayList<>();
        collect(root, name, out);
        return out;
    }

    private static void collect(Node node, String name, List<Element> out) {
        if (hasTagName(node, name)) {
            out.add((Element) node);
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collect(child, name, out);
        }
    }

    static List<Element> children(Node node, String... names) {
        List<Element> out = new ArrayList<>();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            for (String name : names) {
                if (hasTagName(child, name)) {
                    out.add((Element) child);
                    break;
                }
            }
        }
        return out;
    }

    static @Nullable String text(Node node) {
        Node child = node.getFirstChild();
        if (child == null || !isText(child)) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        while (child != null && isText(child)) {
            text.append(child.getNodeValue());
            child = child.getNextSibling();
        }
        return text.toString();
    }

    private static boolean isText(Node node) {
        return node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE;
    }
}
