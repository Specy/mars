package mars.config;

public class SettingsProperties extends ConfigMap {
    public static final String AssembleAll = "AssembleAll";
    public static final String AssembleOnOpen = "AssembleOnOpen";
    public static final String BareMachine = "BareMachine";
    public static final String DataSegmentHighlighting = "DataSegmentHighlighting";
    public static final String DelayedBranching = "DelayedBranching";
    public static final String DisplayAddressesInHex = "DisplayAddressesInHex";
    public static final String DisplayValuesInHex = "DisplayValuesInHex";
    public static final String EditorCurrentLineHighlighting = "EditorCurrentLineHighlighting";
    public static final String EditorLineNumbersDisplayed = "EditorLineNumbersDisplayed";
    public static final String ExtendedAssembler = "ExtendedAssembler";
    public static final String LabelWindowVisibility = "LabelWindowVisibility";
    public static final String LoadExceptionHandler = "LoadExceptionHandler";
    public static final String ProgramArguments = "ProgramArguments";
    public static final String RegistersHighlighting = "RegistersHighlighting";
    public static final String StartAtMain = "StartAtMain";
    public static final String WarningsAreErrors = "WarningsAreErrors";
    public static final String PopupInstructionGuidance = "PopupInstructionGuidance";
    public static final String EditorPopupPrefixLength = "EditorPopupPrefixLength";
    public static final String EvenRowBackground = "EvenRowBackground";
    public static final String EvenRowForeground = "EvenRowForeground";
    public static final String OddRowBackground = "OddRowBackground";
    public static final String OddRowForeground = "OddRowForeground";
    public static final String TextSegmentHighlightBackground = "TextSegmentHighlightBackground";
    public static final String TextSegmentHighlightForeground = "TextSegmentHighlightForeground";
    public static final String TextSegmentDelaySlotHighlightBackground = "TextSegmentDelaySlotHighlightBackground";
    public static final String TextSegmentDelaySlotHighlightForeground = "TextSegmentDelaySlotHighlightForeground";
    public static final String DataSegmentHighlightBackground = "DataSegmentHighlightBackground";
    public static final String DataSegmentHighlightForeground = "DataSegmentHighlightForeground";
    public static final String RegisterHighlightBackground = "RegisterHighlightBackground";
    public static final String RegisterHighlightForeground = "RegisterHighlightForeground";

    public void reset() {
        put(AssembleAll, "false");
        put(AssembleOnOpen, "false");
        put(BareMachine, "false");
        put(DataSegmentHighlighting, "true");
        put(DelayedBranching, "false");
        put(DisplayAddressesInHex, "true");
        put(DisplayValuesInHex, "true");
        put(EditorCurrentLineHighlighting, "true");
        put(EditorLineNumbersDisplayed, "true");
        put(ExtendedAssembler, "true");
        put(LabelWindowVisibility, "false");
        put(LoadExceptionHandler, "false");
        put(ProgramArguments, "false");
        put(RegistersHighlighting, "true");
        put(StartAtMain, "false");
        put(WarningsAreErrors, "false");
        put(PopupInstructionGuidance, "true");
        put(EditorPopupPrefixLength, "2");
        put(EvenRowBackground, "0x00e0e0e0");
        put(EvenRowForeground, "0");
        put(OddRowBackground, "0x00ffffff");
        put(OddRowForeground, "0");
        put(TextSegmentHighlightBackground, "0x00ffff99");
        put(TextSegmentHighlightForeground, "0");
        put(TextSegmentDelaySlotHighlightBackground, "0x33ff00");
        put(TextSegmentDelaySlotHighlightForeground, "0");
        put(DataSegmentHighlightBackground, "0x0099ccff");
        put(DataSegmentHighlightForeground, "0");
        put(RegisterHighlightBackground, "0x0099cc55");
        put(RegisterHighlightForeground, "0");
    }
}