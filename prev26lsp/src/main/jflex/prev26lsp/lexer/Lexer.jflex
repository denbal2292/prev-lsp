package prev26lsp.lexer;

import prev26lsp.parser.Symbol;

%%

%public
%class Lexer
%unicode
%type Token
%function yylex

%{
    // Trivia handling strategy:
    // https://github.com/swiftlang/swift/tree/a4378151d80b7bac8fd827b03fc8db5c7906bfc8/lib/Syntax#trivia
    private Token pendingToken = null;
    private int pendingTriviaWidth = 0;

    private boolean inTrailingTrivia = false;

    private Token addTrivia(boolean isLineBreak) {
        int triviaWidth = yylength();
        // Trivia trivia = new Trivia(kind, yylength());

        if (this.inTrailingTrivia) {
            pendingToken.addTrailingTriviaWidth(triviaWidth);
        } else {
            this.pendingTriviaWidth += triviaWidth;
        }

        // Emit the pending token when a new line is encountered.
        if (isLineBreak) {
            this.inTrailingTrivia = false;
            Token tokenToEmit = pendingToken;
            this.pendingToken = null;

            return tokenToEmit;
        }

        return null;
    }

    private Token emit(Symbol type) {
        return emit(type, null);
    }

    private Token emit(Symbol type, String errorMessage) {
        Token oldPendingToken = pendingToken;
        Token newToken = (errorMessage == null) ?
                         new Token(type, yytext()) :
                         new Token(type, yytext(), errorMessage);

        newToken.addLeadingTriviaWidth(this.pendingTriviaWidth);
        this.pendingTriviaWidth = 0;

        this.pendingToken = newToken;
        this.inTrailingTrivia = true;

        return oldPendingToken;
    }

    public Token nextToken() {
        try {
            Token t;
            // Don't emit a new token until all trailing trivia is collected
            while ((t = yylex()) == null);

            return t;
        } catch (java.io.IOException e) {
            throw new RuntimeException("Error reading input", e);
        }
    }

%}

// https://jflex.de/manual.html
HexDigit           = [0-9A-F]

NonNlChar          = [^\r\n]
LineTerminator     = \r|\n|\r\n

AsciiHexChar       = "\\x" {HexDigit} {HexDigit}
BadHexChar         = "\\x" {NonNlChar}? {NonNlChar}?

// Quote              = "'"
// DoubleQuote        = "\""

EscapedBackslash   = "\\\\"
EscapedQuote       = "\\\'"
EscapedDoubleQuote = "\\\""
BadEscape          = "\\" {NonNlChar}?

NonQuoteChar       = [^'\r\n]
NonDoubleQuoteChar = [^\"\r\n]

PrintableAscii     = [\u0020-\u007E]
ControlCharacter   = [\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]

Character          = [\u0020-\u0026\u0028-\u005b\u005d-\u007E] // Any printable ASCII character except \ (0x27) and ' (0x5c)
                   | {EscapedBackslash}
                   | {EscapedQuote}
                   | {AsciiHexChar}

StringCharacter    = [\u0020-\u0021\u0023-\u005b\u005d-\u007E] // Any printable ASCII character except \ (0x27) and ' (0x22)
                   | {EscapedBackslash}
                   | {EscapedDoubleQuote}
                   | {AsciiHexChar}

Comment           = "//" {PrintableAscii}*
BadComment        = "//" {NonNlChar}*

%%

// Integer literals
0 | [1-9][0-9]*  { return emit(Symbol.INTCONST); }
0 [0-9]+         { return emit(Symbol.INTCONST, "Leading zeros are not allowed in integer literals"); }

// Character literals
"'" {Character} "'"                     { return emit(Symbol.CHARCONST); }
"'" "'"                                 { return emit(Symbol.CHARCONST, "Empty character constant"); }
"'" {BadEscape} "'"                     { return emit(Symbol.CHARCONST, "Character constant contains an invalid escape sequence"); }
"'" {BadHexChar} "'"                    { return emit(Symbol.CHARCONST, "Character constant contains an invalid hexadecimal escape sequence"); }
"'" {NonQuoteChar} "'"                  { return emit(Symbol.CHARCONST, "Character constant contains an illegal character"); }
"'" {NonQuoteChar} {NonQuoteChar}+ "'"  { return emit(Symbol.CHARCONST, "Character constant contains too many characters"); }
"'" {NonQuoteChar}*                     { return emit(Symbol.CHARCONST, "Unterminated character constant"); }

// String literals
"\"" {StringCharacter}* "\""                                    { return emit(Symbol.STRINGCONST); }
"\"" {StringCharacter}* {BadEscape} {StringCharacter}* "\""     { return emit(Symbol.STRINGCONST, "String constant contains an invalid escape sequence (only \\\\ and \\\" are allowed)"); }
"\"" {StringCharacter}* {BadHexChar} {StringCharacter}* "\""    { return emit(Symbol.STRINGCONST, "String constant contains an invalid hexadecimal escape sequence (only \\xHH is allowed)"); }
"\"" {NonDoubleQuoteChar}+ "\""                                 { return emit(Symbol.STRINGCONST, "String constant contains an illegal character"); }
"\"" {NonDoubleQuoteChar}*                                      { return emit(Symbol.STRINGCONST, "Unterminated string constant"); }

// Symbols and keywords
"=="  { return emit(Symbol.EQU); }
"!="  { return emit(Symbol.NEQ); }
"<="  { return emit(Symbol.LEQ); }
">="  { return emit(Symbol.GEQ); }
"."   { return emit(Symbol.DOT); }
","   { return emit(Symbol.COMMA); }
":"   { return emit(Symbol.COLON); }
"="   { return emit(Symbol.ASSIGN); }
"+"   { return emit(Symbol.PLUS); }
"-"   { return emit(Symbol.MINUS); }
"*"   { return emit(Symbol.STAR); }
"/"   { return emit(Symbol.SLASH); }
"%"   { return emit(Symbol.PERCENT); }
"<"   { return emit(Symbol.LT); }
">"   { return emit(Symbol.GT); }
"("   { return emit(Symbol.LPAREN); }
")"   { return emit(Symbol.RPAREN); }
"["   { return emit(Symbol.LSQUARE); }
"]"   { return emit(Symbol.RSQUARE); }
"{"   { return emit(Symbol.LCURLY); }
"}"   { return emit(Symbol.RCURLY); }
"^"   { return emit(Symbol.CARET); }

/* 3. Reserved words */
"and"    { return emit(Symbol.AND); }
"as"     { return emit(Symbol.AS); }
"bool"   { return emit(Symbol.BOOL); }
"do"     { return emit(Symbol.DO); }
"char"   { return emit(Symbol.CHAR); }
"else"   { return emit(Symbol.ELSE); }
"end"    { return emit(Symbol.END); }
"false"  { return emit(Symbol.FALSE); }
"fun"    { return emit(Symbol.FUN); }
"if"     { return emit(Symbol.IF); }
"in"     { return emit(Symbol.IN); }
"int"    { return emit(Symbol.INT); }
"let"    { return emit(Symbol.LET); }
"nil"    { return emit(Symbol.NIL); }
"none"   { return emit(Symbol.NONE); }
"not"    { return emit(Symbol.NOT); }
"or"     { return emit(Symbol.OR); }
"sizeof" { return emit(Symbol.SIZEOF); }
"then"   { return emit(Symbol.THEN); }
"true"   { return emit(Symbol.TRUE); }
"typ"    { return emit(Symbol.TYP); }
"var"    { return emit(Symbol.VAR); }
"void"   { return emit(Symbol.VOID); }
"while"  { return emit(Symbol.WHILE); }

// Identifier
[a-zA-Z_][a-zA-Z0-9_]*  { return emit(Symbol.ID); }

// Trivia
{Comment}        { return addTrivia(false); }
{BadComment}     { return addTrivia(false); } // System.err.println("Warning: Comment contains invalid characters: " + yytext());
[ \t]+           { return addTrivia(false); }
{LineTerminator} { return addTrivia(true); }

// Invalid character
{ControlCharacter} { return emit(Symbol.ID, "Control characters are not allowed"); }
. { return emit(Symbol.ID, "Invalid character"); }

<<EOF>> { return emit(Symbol.EOF); }
