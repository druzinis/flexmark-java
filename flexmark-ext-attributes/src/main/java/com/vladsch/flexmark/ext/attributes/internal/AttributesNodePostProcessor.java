package com.vladsch.flexmark.ext.attributes.internal;

import com.vladsch.flexmark.ast.*;
import com.vladsch.flexmark.ext.attributes.AttributeNode;
import com.vladsch.flexmark.ext.attributes.AttributesDelimiter;
import com.vladsch.flexmark.ext.attributes.AttributesExtension;
import com.vladsch.flexmark.ext.attributes.AttributesNode;
import com.vladsch.flexmark.parser.block.NodePostProcessor;
import com.vladsch.flexmark.parser.block.NodePostProcessorFactory;
import com.vladsch.flexmark.util.NodeTracker;
import com.vladsch.flexmark.util.ast.BlankLine;
import com.vladsch.flexmark.util.ast.DoNotAttributeDecorate;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

import java.util.ArrayList;

public class AttributesNodePostProcessor extends NodePostProcessor {
    private final NodeAttributeRepository nodeAttributeRepository;
    private final AttributesOptions myOptions;

    public AttributesNodePostProcessor(Document document) {
        nodeAttributeRepository = AttributesExtension.NODE_ATTRIBUTES.getFrom(document);
        myOptions = new AttributesOptions(document);
    }

    public Node getAttributeOwner(NodeTracker state, AttributesNode attributesNode) {
        Node previous = attributesNode.getPreviousAnyNot(BlankLine.class, DoNotAttributeDecorate.class);
        Node next = attributesNode.getNext();
        Node attributeOwner;
        Node parent = attributesNode.getParent();

        if (previous == null) {
            // attributes are first
            // if parent is a paragraph
            //      if paragraph parent is a paragraph item container
            //          if paragraph has no previous sibling then
            //              1. attributes go to the paragraph parent's parent
            //          else
            //              if paragraph only contains attributes then
            //                  2. attributes go to paragraph's previous sibling,
            //              else
            //                  3. attributes go to the paragraph
            //      else
            //          if paragraph only contains attributes then
            //              if paragraph has no previous sibling then
            //                  4. attributes go to paragraph's parent
            //              else
            //                  5. attributes go to paragraph's previous sibling,
            //          else
            //              6. attributes go to the paragraph
            // else
            //      7. attributes go to the parent
            if (parent instanceof Paragraph) {
                if (parent.getParent() instanceof ParagraphItemContainer) {
                    Node parentPreviousNotBlankLine = parent.getPreviousAnyNot(BlankLine.class);
                    if (parentPreviousNotBlankLine == null) {
                        //              1. attributes go to the paragraph parent's parent
                        attributeOwner = parent.getParent().getParent();
                    } else {
                        if (attributesNode.getNextAnyNot(AttributesNode.class, BlankLine.class) == null) {
                            //                  2. attributes go to paragraph's previous sibling,
                            attributeOwner = parentPreviousNotBlankLine;
                        } else {
                            //                  3. attributes go to the paragraph
                            attributeOwner = parent;
                        }
                    }
                } else {
                    if (attributesNode.getNextAnyNot(AttributesNode.class, BlankLine.class) == null) {
                        Node parentPreviousNotBlankLine = parent.getPreviousAnyNot(BlankLine.class);
                        if (parentPreviousNotBlankLine == null) {
                            //                  4. attributes go to paragraph's parent
                            attributeOwner = parent.getParent();
                        } else {
                            //                  5. attributes go to paragraph's previous sibling,
                            attributeOwner = parentPreviousNotBlankLine;
                        }
                    } else {
                        //              6. attributes go to the paragraph
                        attributeOwner = parent;
                    }
                }
            } else {
                //      7. attributes go to the parent
                attributeOwner = parent;
            }
        } else {
            if ((!myOptions.assignTextAttributes && (previous instanceof Text || previous instanceof TextBase)) || previous.getChars().getEndOffset() < attributesNode.getStartOffset()) {
                // either previous is text and no text attributes or not attached to the previous node
                // then attributes go to parent unless overridden by delimited attribute spans 
                
                if (myOptions.useEmptyImplicitAsSpanDelimiter) {
                    // find first previous not delimited by unmatched attribute
                    previous = matchDelimitedSpans(state, attributesNode, previous);
                }

                if (previous instanceof TextBase) {
                    // use delimited span
                    attributeOwner = previous;
                } else if (parent instanceof Paragraph && parent.getParent() instanceof ParagraphItemContainer) {
                    attributeOwner = parent.getParent();
                } else {
                    attributeOwner = parent;
                }
            } else {
                // attached, attributes go to previous node, but may need to wrap spans containing DoNotAttributeDecorate in TextBase
                if (myOptions.wrapNonAttributeText) {
                    // find first previous not delimited by attribute
                    Node first = attributesNode.getPrevious();
                    Node lastNonAttributesNode = null;
                    boolean hadDoNotDecorate = false;

                    while (first != null && !(first instanceof AttributesNode)) {
                        if (first instanceof DoNotAttributeDecorate) {
                            hadDoNotDecorate = true;
                        }
                        lastNonAttributesNode = first;
                        first = first.getPrevious();
                    }

                    if (hadDoNotDecorate) {
                        // need to wrap in text base from first to attribute node
                        TextBase textBase = new TextBase();
                        textBaseWrap(state, lastNonAttributesNode, attributesNode, textBase);
                        previous = textBase;
                    }
                }

                if (myOptions.useEmptyImplicitAsSpanDelimiter) {
                    // find first previous not delimited by unmatched attribute
                    previous = matchDelimitedSpans(state, attributesNode, previous);
                }

                if (previous instanceof Text) {
                    // insert text base where text was
                    TextBase textBase = new TextBase(previous.getChars());
                    previous.insertBefore(textBase);
                    previous.unlink();
                    state.nodeRemoved(previous);

                    textBase.appendChild(previous);
                    state.nodeAddedWithChildren(textBase);
                    attributeOwner = textBase;
                } else if (previous instanceof AttributesDelimiter) {
                    // no owner, attributes go into aether
                    attributeOwner = null;
                } else {
                    if (previous instanceof AttributesNode) {
                        // we are spliced right up against previous attributes, give our attributes to the owner of previous attributes
                        attributeOwner = getAttributeOwner(state, (AttributesNode) previous);
                    } else {
                        attributeOwner = previous;
                    }
                }
            }
        }
        return attributeOwner;
    }

    static Node matchDelimitedSpans(NodeTracker state, AttributesNode attributesNode, Node previous) {
        Node first = attributesNode.getPrevious();
        Node lastNonAttributesNode = null;
        ArrayList<Node> unmatchedAttributes = new ArrayList<>();

        while (first != null) {
            if (first instanceof AttributesDelimiter) {
                if (!unmatchedAttributes.isEmpty()) {
                    // match it and wrap in text
                    Node lastNode = unmatchedAttributes.remove(unmatchedAttributes.size() - 1);
                    lastNonAttributesNode = first.getNext();
                    if (lastNode != lastNonAttributesNode) {
                        TextBase textBase = new TextBase();

                        textBaseWrap(state, lastNonAttributesNode, lastNode, textBase);
                        lastNonAttributesNode = textBase;
                    } else {
                        previous = first;
                    }
                } else {
                    // unmatched delimiter is our start span
                    TextBase textBase = new TextBase();
                    lastNonAttributesNode = first.getNext();

                    if (lastNonAttributesNode != attributesNode) {
                        textBaseWrap(state, lastNonAttributesNode, attributesNode, textBase);
                        previous = textBase;
                    } else {
                        previous = first;
                    }
                    break;
                }
            } else if (first instanceof AttributesNode) {
                unmatchedAttributes.add(first);
            } else {
                lastNonAttributesNode = first;
            }

            first = first.getPrevious();
        }

        if (!unmatchedAttributes.isEmpty()) {
            // use the first unmatched as our end of attribute span
            previous = unmatchedAttributes.get(0);
            Node previousNext = previous.getNext();
            if (previousNext != null && previousNext != attributesNode) {
                previous = previousNext;
            }
        }
        
        return previous;
    }

    static void textBaseWrap(final NodeTracker state, Node lastNonAttributesNode, final Node lastNode, final TextBase textBase) {
        while (lastNonAttributesNode != lastNode) {
            Node nextNode = lastNonAttributesNode.getNext();
            lastNonAttributesNode.unlink();
            state.nodeRemoved(lastNonAttributesNode);
            textBase.appendChild(lastNonAttributesNode);
            lastNonAttributesNode = nextNode;
        }
        textBase.setCharsFromContent();
        lastNode.insertBefore(textBase);
        state.nodeAddedWithDescendants(textBase);
    }
    
    @Override
    public void process(NodeTracker state, Node node) {
        if (node instanceof AttributesNode) {
            AttributesNode attributesNode = (AttributesNode) node;

            // apply to sibling unless the sibling is a Text node then apply it to the parent
            Node previous = attributesNode.getPrevious();
            Node next = attributesNode.getNext();

            // need to trim end of previous sibling if we are last
            // and to trim start of next sibling if we are first
            if (previous == null) {
                // we are first, trim start of next sibling
                if (next != null && !(next instanceof AttributesNode)) {
                    if (next.getChars().isBlank()) {
                        // remove this node it is all blank
                        Node tmp = next;
                        next = next.getNext();
                        tmp.unlink();
                        state.nodeRemoved(tmp);
                    } else {
                        next.setChars(next.getChars().trimStart());
                    }
                }
            }

            if (next == null) {
                // we are last, trim end of prev sibling
                if (previous != null && !(previous instanceof AttributesNode)) {
                    if (previous.getChars().isBlank()) {
                        // remove this node it is all blank
                        Node tmp = previous;
                        previous = previous.getPrevious();
                        tmp.unlink();
                        state.nodeRemoved(tmp);
                    } else {
                        previous.setChars(previous.getChars().trimEnd());
                    }
                }
            }

            Node attributeOwner = getAttributeOwner(state, attributesNode);
            if (attributeOwner != null) {
                nodeAttributeRepository.put(attributeOwner, attributesNode);

                // set the heading id for this node so the correct id will be used
                if (attributeOwner instanceof AnchorRefTarget) {
                    for (Node attributeNode : attributesNode.getReversedChildren()) {
                        if (attributeNode instanceof AttributeNode) {
                            if (((AttributeNode) attributeNode).isId()) {
                                ((AnchorRefTarget) attributeOwner).setAnchorRefId(((AttributeNode) attributeNode).getValue().toString());
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    public static class Factory extends NodePostProcessorFactory {
        public Factory() {
            super(false);
            addNodes(AttributesNode.class);
        }

        @Override
        public NodePostProcessor create(Document document) {
            return new AttributesNodePostProcessor(document);
        }
    }
}
