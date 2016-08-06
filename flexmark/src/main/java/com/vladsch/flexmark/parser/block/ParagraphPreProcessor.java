package com.vladsch.flexmark.parser.block;

import com.vladsch.flexmark.ast.Paragraph;

public interface ParagraphPreProcessor {
    /**
     * Process Paragraph Content on closing of the paragraph block to removeIndex non-text lines.
     * <p>
     * This is used by extensions to take leading lines from a paragraph and convert them
     * to other blocks
     * 
     * by Default leading lines that define references are blockRemoved and Reference nodes are inserted before.
     * @param block     paragraph ast to process  
     * @param state     parser state
     * @return number of characters processed from the start of the block                 
     */

    int preProcessBlock(Paragraph block, ParserState state);
}