package com.pigpurchases.parser;

import java.io.IOException;
import java.nio.file.Path;

/** A parser for one issuer's statement PDFs. */
public interface StatementParser {
    ParsedStatement parse(Path pdf) throws IOException;
}
