package org.unicode.cldr.test;

import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.text.UnicodeSet;
import java.util.ArrayList;
import java.util.List;
import org.unicode.cldr.test.CheckCLDR.CheckStatus.Subtype;
import org.unicode.cldr.test.CheckCLDR.CheckStatus.Type;
import org.unicode.cldr.tool.LikelySubtags;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.CldrUtility;
import org.unicode.cldr.util.LocaleIDParser;
import org.unicode.cldr.util.XPathParts;
import org.unicode.cldr.util.personname.PersonNameFormatter.NamePattern;
import org.unicode.cldr.util.personname.PersonNameFormatter.Optionality;
import org.unicode.cldr.util.personname.PersonNameFormatter.SampleType;

public class CheckPersonNames extends CheckCLDR {

    static final String MISSING = CldrUtility.NO_INHERITANCE_MARKER;

    boolean isRoot = false;
    boolean hasRootParent = false;
    String initialSeparator = " ";

    private UnicodeSet allowedCharacters;

    static final UnicodeSet BASE_ALLOWED =
            new UnicodeSet("[\\p{sc=Common}\\p{sc=Inherited}-\\p{N}-[❮❯∅<>∅0]]").freeze();
    static final UnicodeSet HANI = new UnicodeSet("[\\p{sc=Hani}]").freeze();
    static final UnicodeSet KORE = new UnicodeSet("[\\p{sc=Hang}]").addAll(HANI).freeze();
    static final UnicodeSet JPAN =
            new UnicodeSet("[\\p{sc=Kana}\\p{sc=Hira}]").addAll(HANI).freeze();

    @Override
    public CheckCLDR setCldrFileToCheck(
            CLDRFile cldrFileToCheck, Options options, List<CheckStatus> possibleErrors) {
        String localeId = cldrFileToCheck.getLocaleID();
        isRoot = localeId.equals("root");
        hasRootParent = "root".equals(LocaleIDParser.getParent(localeId));

        // other characters are caught by CheckForExemplars
        String script = new LikelySubtags().getLikelyScript(localeId);
        allowedCharacters =
                new UnicodeSet(BASE_ALLOWED).addAll(getUnicodeSetForScript(script)).freeze();

        String initialPatternSequence =
                cldrFileToCheck.getStringValue(
                        "//ldml/personNames/initialPattern[@type=\"initialSequence\"]");
        initialSeparator = MessageFormat.format(initialPatternSequence, "", "");
        //
        return super.setCldrFileToCheck(cldrFileToCheck, options, possibleErrors);
    }

    public UnicodeSet getUnicodeSetForScript(String script) {
        switch (script) {
            case "Jpan":
                return JPAN;
            case "Kore":
                return KORE;
            case "Hant":
            case "Hans":
                return HANI;
            default:
                return new UnicodeSet("[\\p{sc=" + script + "}]");
        }
    }

    static final UnicodeSet nativeSpaceReplacementValues = new UnicodeSet("[{}\\ ]").freeze();
    static final UnicodeSet foreignSpaceReplacementValues = new UnicodeSet("[\\ ・·]").freeze();

    @Override
    public CheckCLDR handleCheck(
            String path, String fullPath, String value, Options options, List<CheckStatus> result) {
        if (isRoot || !path.startsWith("//ldml/personNames/")) {
            return this;
        }

        XPathParts parts = XPathParts.getFrozenInstance(path);
        switch (parts.getElement(2)) {
            case "personName":
                NamePattern namePattern = NamePattern.from(0, value);
                ArrayList<List<String>> failures =
                        namePattern.findInitialFailures(initialSeparator);
                for (List<String> row : failures) {
                    String previousField = row.get(0);
                    String intermediateLiteral = row.get(1);
                    String followingField = row.get(1);
                    result.add(
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(CheckStatus.errorType)
                                    .setSubtype(Subtype.illegalCharactersInPattern)
                                    .setMessage(
                                            "The gap between {0} and {2} must be the same as the pattern-initialSequence, =“{1}”",
                                            previousField, intermediateLiteral, followingField));
                }

                break;
            case "nativeSpaceReplacement":
                if (!nativeSpaceReplacementValues.contains(value)) {
                    result.add(
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(CheckStatus.errorType)
                                    .setSubtype(Subtype.illegalCharactersInPattern)
                                    .setMessage(
                                            "NativeSpaceReplacement must be space if script requires spaces, and empty otherwise."));
                }
                break;
            case "foreignSpaceReplacement":
                if (!foreignSpaceReplacementValues.contains(value)) {
                    result.add(
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(CheckStatus.errorType)
                                    .setSubtype(Subtype.illegalCharactersInPattern)
                                    .setMessage(
                                            "ForeignSpaceReplacement must be space if script requires spaces."));
                }
                break;
            case "sampleName":
                if (value == null) {
                    break;
                }
                if (!allowedCharacters.containsAll(value)
                        && !value.equals(CldrUtility.NO_INHERITANCE_MARKER)) {
                    UnicodeSet bad = new UnicodeSet().addAll(value).removeAll(allowedCharacters);
                    final Type mainType =
                            getPhase() != Phase.BUILD
                                    ? CheckStatus.errorType
                                    : CheckStatus
                                            .warningType; // we need to be able to check this in
                    // without error
                    result.add(
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(mainType)
                                    .setSubtype(Subtype.badSamplePersonName)
                                    .setMessage(
                                            "Illegal characters in sample name: "
                                                    + bad.toPattern(false)));
                } else if (getCldrFileToCheck().getUnresolved().getStringValue(path) != null) {

                    // (Above) We only check for an error if there is a value in the UNresolved
                    // file.
                    // That is, we don't want MISSING that is inherited from root to cause an error,
                    // unless it is explicitly inherited

                    // We also check that
                    // - if there is a surname2, there must be either a surname or surname.core

                    String message = null;
                    SampleType sampleType = SampleType.valueOf(parts.getAttributeValue(2, "item"));
                    String modifiedField = parts.getAttributeValue(3, "type");
                    boolean isMissingInUnresolved =
                            value.equals(MISSING) || value.equals(CldrUtility.INHERITANCE_MARKER);

                    final Optionality optionality = sampleType.getOptionality(modifiedField);
                    if (isMissingInUnresolved) {
                        if (optionality == Optionality.required) {
                            message = "This value must not be empty (" + MISSING + ")";
                        }
                    } else { // not missing, so...
                        if (optionality == Optionality.disallowed) {
                            message = "This value must be empty (" + MISSING + ")";
                        } else if (modifiedField.equals("surname2")) {
                            String surname =
                                    getCldrFileToCheck()
                                            .getStringValue(path)
                                            .replace("surname2", "surname");
                            String surnameCore =
                                    getCldrFileToCheck()
                                            .getStringValue(path)
                                            .replace("surname2", "surname-core");
                            if (surname.equals(MISSING) && surnameCore.equals(MISSING)) {
                                message =
                                        "The value for '"
                                                + modifiedField
                                                + "' must not be empty ("
                                                + MISSING
                                                + ") unless 'surname2' is.";
                            }
                        }
                    }
                    if (message != null) {
                        getPhase();
                        final Type mainType =
                                getPhase() != Phase.BUILD
                                        ? CheckStatus.errorType
                                        : CheckStatus
                                                .warningType; // we need to be able to check this in
                        // without error
                        result.add(
                                new CheckStatus()
                                        .setCause(this)
                                        .setMainType(mainType)
                                        .setSubtype(Subtype.badSamplePersonName)
                                        .setMessage(message));
                    }
                }
                break;
        }
        return this;
    }
}
