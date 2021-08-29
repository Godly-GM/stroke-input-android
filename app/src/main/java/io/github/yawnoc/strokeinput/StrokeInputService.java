/*
  Copyright 2021 Conway
  Licensed under the GNU General Public License v3.0 (GPL-3.0-only).
  This is free software with NO WARRANTY etc. etc.,
  see LICENSE or <https://www.gnu.org/licenses/>.
*/

package io.github.yawnoc.strokeinput;

import android.annotation.SuppressLint;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import io.github.yawnoc.utilities.Contexty;
import io.github.yawnoc.utilities.Mappy;
import io.github.yawnoc.utilities.Stringy;

/*
  An InputMethodService for the Stroke Input Method (筆畫輸入法).
  TODO:
    - Phrase candidates
    - Actually complete the stroke input data set
*/
public class StrokeInputService
  extends InputMethodService
  implements
    InputContainer.OnInputListener, CandidatesBarAdapter.OnCandidateListener
{
  private static final int BACKSPACE_REPEAT_INTERVAL_MILLISECONDS_ASCII = 50;
  private static final int BACKSPACE_REPEAT_INTERVAL_MILLISECONDS_UTF_8 = 100;
  
  private static final String PREFERENCES_FILE_NAME = "preferences.txt";
  private static final String SEQUENCE_EXACT_CHARACTERS_FILE_NAME =
    "sequence-exact-characters.txt";
  private static final String SEQUENCE_PREFIX_CHARACTERS_FILE_NAME =
    "sequence-prefix-characters.txt";
  private static final String RANKING_FILE_NAME = "ranking.txt";
  
  private static final int USE_PREFIX_DATA_MAX_STROKE_COUNT = 3;
  private static final int MAX_PREFIX_MATCH_COUNT = 20;
  
  Keyboard strokesKeyboard;
  Keyboard strokesSymbols1Keyboard;
  Keyboard strokesSymbols2Keyboard;
  Keyboard qwertyKeyboard;
  Keyboard qwertySymbolsKeyboard;
  
  private Map<Keyboard, String> nameFromKeyboard;
  private Map<String, Keyboard> keyboardFromName;
  private Set<Keyboard> keyboardSet;
  
  private InputContainer inputContainer;
  
  private NavigableMap<String, CharactersData>
    exactCharactersDataFromStrokeDigitSequence;
  private Map<String, CharactersData>
    prefixCharactersDataFromStrokeDigitSequence;
  
  private Map<String, Integer> sortingRankFromCharacter;
  private Comparator<String> candidateComparator;
  
  private String strokeDigitSequence = "";
  private List<String> candidateList = new ArrayList<>();
  
  private int inputOptionsBits;
  private boolean enterKeyHasAction;
  private boolean inputIsPassword;
  
  @Override
  public View onCreateInputView() {
    
    initialiseKeyboards();
    initialiseInputContainer();
    
    final long initialiseStrokeInputStartMillis = System.currentTimeMillis();
    initialiseStrokeInput();
    final long initialiseStrokeInputEndMillis = System.currentTimeMillis();
    Log.i(
      "StrokeInputService",
      "initialiseStrokeInput() took "
        + (initialiseStrokeInputEndMillis - initialiseStrokeInputStartMillis)
        + " milliseconds."
    );
    
    return inputContainer;
  }
  
  private void initialiseKeyboards() {
    
    strokesKeyboard = newKeyboard(R.xml.keyboard_strokes);
    strokesSymbols1Keyboard = newKeyboard(R.xml.keyboard_strokes_symbols_1);
    strokesSymbols2Keyboard = newKeyboard(R.xml.keyboard_strokes_symbols_2);
    qwertyKeyboard = newKeyboard(R.xml.keyboard_qwerty);
    qwertySymbolsKeyboard = newKeyboard(R.xml.keyboard_qwerty_symbols);
    
    nameFromKeyboard = new HashMap<>();
    nameFromKeyboard.put(strokesKeyboard, "STROKES");
    nameFromKeyboard.put(strokesSymbols1Keyboard, "STROKES_SYMBOLS_1");
    nameFromKeyboard.put(strokesSymbols2Keyboard, "STROKES_SYMBOLS_2");
    nameFromKeyboard.put(qwertyKeyboard, "QWERTY");
    nameFromKeyboard.put(qwertySymbolsKeyboard, "QWERTY_SYMBOLS");
    keyboardFromName = Mappy.invertMap(nameFromKeyboard);
    keyboardSet = nameFromKeyboard.keySet();
  }
  
  private Keyboard newKeyboard(final int layoutResourceId) {
    return new Keyboard(this, layoutResourceId, isFullscreenMode());
  }
  
  @SuppressLint("InflateParams")
  private void initialiseInputContainer() {
    inputContainer =
      (InputContainer)
        getLayoutInflater().inflate(R.layout.input_container, null);
    inputContainer.setOnInputListener(this);
    inputContainer.setCandidateListener(this);
    inputContainer.setKeyboard(loadSavedKeyboard());
  }
  
  private void initialiseStrokeInput() {
    
    exactCharactersDataFromStrokeDigitSequence = new TreeMap<>();
    
    try {
      
      final InputStream inputStream =
        getAssets().open(SEQUENCE_EXACT_CHARACTERS_FILE_NAME);
      final BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(inputStream));
      
      String line;
      while ((line = bufferedReader.readLine()) != null) {
        if (!isCommentLine(line)) {
          putSequenceAndCharactersDataIntoMap(
            line,
            exactCharactersDataFromStrokeDigitSequence
          );
        }
      }
    }
    catch (IOException exception) {
      exception.printStackTrace();
    }
    
    prefixCharactersDataFromStrokeDigitSequence = new HashMap<>();
    
    try {
      
      final InputStream inputStream =
        getAssets().open(SEQUENCE_PREFIX_CHARACTERS_FILE_NAME);
      final BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(inputStream));
      
      String line;
      while ((line = bufferedReader.readLine()) != null) {
        if (!isCommentLine(line)) {
          putSequenceAndCharactersDataIntoMap(
            line,
            prefixCharactersDataFromStrokeDigitSequence
          );
        }
      }
    }
    catch (IOException exception) {
      exception.printStackTrace();
    }
    
    sortingRankFromCharacter = new HashMap<>();
    
    try {
      
      final InputStream inputStream = getAssets().open(RANKING_FILE_NAME);
      final BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(inputStream));
      
      int currentRank = 0;
      String line;
      while ((line = bufferedReader.readLine()) != null) {
        if (!isCommentLine(line)) {
          for (final String character : Stringy.toCharacterList(line)) {
            if (!sortingRankFromCharacter.containsKey(character)) {
              currentRank++;
              sortingRankFromCharacter.put(character, currentRank);
            }
          }
        }
      }
    }
    catch (IOException exception) {
      exception.printStackTrace();
    }
    
    candidateComparator =
      (character1, character2) -> {
        Integer rank1 = sortingRankFromCharacter.get(character1);
        if (rank1 == null) {
          rank1 = Integer.MAX_VALUE;
        }
        Integer rank2 = sortingRankFromCharacter.get(character2);
        if (rank2 == null) {
          rank2 = Integer.MAX_VALUE;
        }
        return rank1 - rank2;
      };
  }
  
  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean isCommentLine(final String line) {
    return line.startsWith("#") || line.length() == 0;
  }
  
  private void putSequenceAndCharactersDataIntoMap(
    final String line,
    final Map<String, CharactersData> charactersDataFromStrokeDigitSequence
  )
  {
    final String[] sunderedLineArray = Stringy.sunder(line, "\t");
    
    final String strokeDigitSequence = sunderedLineArray[0];
    final String commaSeparatedCharacters = sunderedLineArray[1];
    
    charactersDataFromStrokeDigitSequence.put(
      strokeDigitSequence,
      new CharactersData(commaSeparatedCharacters)
    );
  }
  
  @Override
  public void onStartInput(
    final EditorInfo editorInfo,
    final boolean isRestarting
  )
  {
    super.onStartInput(editorInfo, isRestarting);
    
    inputOptionsBits = editorInfo.imeOptions;
    enterKeyHasAction =
      (inputOptionsBits & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0;
    
    final int inputTypeBits = editorInfo.inputType;
    final int inputClassBits =
      inputTypeBits & InputType.TYPE_MASK_CLASS;
    final int inputVariationBits =
      inputTypeBits & InputType.TYPE_MASK_VARIATION;
    
    switch (inputClassBits) {
      
      case InputType.TYPE_CLASS_NUMBER:
        inputIsPassword =
          inputVariationBits == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        break;
      
      case InputType.TYPE_CLASS_TEXT:
        switch (inputVariationBits) {
          case InputType.TYPE_TEXT_VARIATION_PASSWORD:
          case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
          case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
            inputIsPassword = true;
            break;
          default:
            inputIsPassword = false;
        }
        break;
      
      default:
        inputIsPassword = false;
    }
  }
  
  @Override
  public void onStartInputView(
    final EditorInfo editorInfo,
    final boolean isRestarting
  )
  {
    super.onStartInputView(editorInfo, isRestarting);
    setEnterKeyDisplayText();
    inputContainer.showStrokeSequenceBar();
    inputContainer.showCandidatesBar();
    inputContainer.showKeyPreviewPlane();
  }
  
  private void setEnterKeyDisplayText() {
    
    String enterKeyDisplayText = null;
    switch (inputOptionsBits & EditorInfo.IME_MASK_ACTION) {
      case EditorInfo.IME_ACTION_DONE:
        enterKeyDisplayText = getString(R.string.display_text__done);
        break;
      case EditorInfo.IME_ACTION_GO:
        enterKeyDisplayText = getString(R.string.display_text__go);
        break;
      case EditorInfo.IME_ACTION_NEXT:
        enterKeyDisplayText = getString(R.string.display_text__next);
        break;
      case EditorInfo.IME_ACTION_PREVIOUS:
        enterKeyDisplayText = getString(R.string.display_text__previous);
        break;
      case EditorInfo.IME_ACTION_SEARCH:
        enterKeyDisplayText = getString(R.string.display_text__search);
        break;
      case EditorInfo.IME_ACTION_SEND:
        enterKeyDisplayText = getString(R.string.display_text__send);
        break;
    }
    if (!enterKeyHasAction || enterKeyDisplayText == null) {
      enterKeyDisplayText = getString(R.string.display_text__return);
    }
    
    for (final Keyboard keyboard : keyboardSet) {
      for (final Key key : keyboard.getKeyList()) {
        if (key.valueText.equals("ENTER")) {
          key.displayText = enterKeyDisplayText;
        }
      }
    }
  }
  
  @Override
  public void onComputeInsets(final InputMethodService.Insets insets) {
    super.onComputeInsets(insets);
    if (inputContainer != null) { // check needed in API level 30
      final int touchableTopY = inputContainer.getTouchableTopY();
      // API level 28 is dumb, see <https://stackoverflow.com/a/53326786>
      if (touchableTopY > 0) {
        insets.visibleTopInsets = touchableTopY;
        insets.contentTopInsets = touchableTopY;
      }
    }
  }
  
  @Override
  public void onKey(final String valueText) {
    
    final InputConnection inputConnection = getCurrentInputConnection();
    if (inputConnection == null) {
      return;
    }
    
    switch (valueText) {
      
      case "STROKE_1":
      case "STROKE_2":
      case "STROKE_3":
      case "STROKE_4":
      case "STROKE_5": {
        final String strokeDigit = Stringy.removePrefix("STROKE_", valueText);
        final String newStrokeDigitSequence =
          strokeDigitSequence + strokeDigit;
        final List<String> newCandidateList =
          toCandidateList(newStrokeDigitSequence);
        if (newCandidateList.size() > 0) {
          setStrokeDigitSequence(newStrokeDigitSequence);
          setCandidateList(newCandidateList);
        }
        break;
      }
      
      case "BACKSPACE": {
        if (strokeDigitSequence.length() > 0) {
          final String newStrokeDigitSequence =
            Stringy.removeSuffix(".", strokeDigitSequence);
          final List<String> newCandidateList =
            toCandidateList(newStrokeDigitSequence);
          setStrokeDigitSequence(newStrokeDigitSequence);
          setCandidateList(newCandidateList);
          inputContainer.setKeyRepeatIntervalMilliseconds(
            BACKSPACE_REPEAT_INTERVAL_MILLISECONDS_UTF_8
          );
        }
        else {
          inputConnection.sendKeyEvent(
            new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
          );
          inputConnection.sendKeyEvent(
            new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
          );
          final int nextBackspaceIntervalMilliseconds = (
            Stringy.isAscii(getTextBeforeCursor(inputConnection, 1))
              ? BACKSPACE_REPEAT_INTERVAL_MILLISECONDS_ASCII
              : BACKSPACE_REPEAT_INTERVAL_MILLISECONDS_UTF_8
          );
          inputContainer.setKeyRepeatIntervalMilliseconds(
            nextBackspaceIntervalMilliseconds
          );
        }
        break;
      }
      
      case "SWITCH_TO_STROKES":
      case "SWITCH_TO_STROKES_SYMBOLS_1":
      case "SWITCH_TO_STROKES_SYMBOLS_2":
      case "SWITCH_TO_QWERTY":
      case "SWITCH_TO_QWERTY_SYMBOLS":
        final String keyboardName =
          Stringy.removePrefix("SWITCH_TO_", valueText);
        final Keyboard keyboard = keyboardFromName.get(keyboardName);
        inputContainer.setKeyboard(keyboard);
        break;
      
      case "SPACE":
        if (strokeDigitSequence.length() > 0) {
          onCandidate(getFirstCandidate());
        }
        inputConnection.commitText(" ", 1);
        break;
      
      case "ENTER":
        if (strokeDigitSequence.length() > 0) {
          onCandidate(getFirstCandidate());
        }
        else if (enterKeyHasAction) {
          inputConnection.performEditorAction(inputOptionsBits);
        }
        else {
          inputConnection.commitText("\n", 1);
        }
        break;
      
      default:
        if (strokeDigitSequence.length() > 0) {
          onCandidate(getFirstCandidate());
        }
        inputConnection.commitText(valueText, 1);
    }
  }
  
  @Override
  public void onLongPress(final String valueText) {
    
    if (valueText.equals("SPACE")) {
      Contexty.showSystemKeyboardSwitcher(this);
    }
    else if (valueText.equals("ENTER")) {
      inputContainer.toggleDebugMode();
    }
  }
  
  @Override
  public void onSwipe(final String valueText) {
    
    if (valueText.equals("SPACE")) {
      
      final Keyboard keyboard = inputContainer.getKeyboard();
      final String keyboardName = nameFromKeyboard.get(keyboard);
      
      if (keyboardName == null) {
        return;
      }
      switch (keyboardName) {
        case "STROKES":
        case "STROKES_SYMBOLS_1":
        case "STROKES_SYMBOLS_2":
          inputContainer.setKeyboard(qwertyKeyboard);
          break;
        case "QWERTY":
        case "QWERTY_SYMBOLS":
          inputContainer.setKeyboard(strokesKeyboard);
          break;
      }
    }
  }
  
  @Override
  public Keyboard loadSavedKeyboard() {
    final String savedKeyboardName =
      Contexty.loadPreferenceString(
        getApplicationContext(),
        PREFERENCES_FILE_NAME,
        "keyboardName"
      );
    final Keyboard savedKeyboard = keyboardFromName.get(savedKeyboardName);
    if (savedKeyboard == null) {
      return strokesKeyboard;
    }
    else {
      return savedKeyboard;
    }
  }
  
  @Override
  public void saveKeyboard(final Keyboard keyboard) {
    final String keyboardName = nameFromKeyboard.get(keyboard);
    Contexty.savePreferenceString(
      getApplicationContext(),
      PREFERENCES_FILE_NAME,
      "keyboardName",
      keyboardName
    );
  }
  
  @Override
  public void onCandidate(final String candidate) {
    
    final InputConnection inputConnection = getCurrentInputConnection();
    if (inputConnection == null) {
      return;
    }
    
    inputConnection.commitText(candidate, 1);
    setStrokeDigitSequence("");
  }
  
  private void setStrokeDigitSequence(final String strokeDigitSequence) {
    this.strokeDigitSequence = strokeDigitSequence;
    inputContainer.setStrokeDigitSequence(strokeDigitSequence);
  }
  
  private void setCandidateList(final List<String> candidateList) {
    this.candidateList = candidateList;
    inputContainer.setCandidateList(candidateList);
  }
  
  private List<String> toCandidateList(final String strokeDigitSequence) {
    
    final CharactersData exactMatchCharactersData =
      exactCharactersDataFromStrokeDigitSequence.get(strokeDigitSequence);
    final List<String> exactMatchCandidatesList = (
      exactMatchCharactersData == null
        ? Collections.emptyList()
        : exactMatchCharactersData.toCandidateList(candidateComparator)
    );
    
    final CharactersData prefixMatchCharactersData;
    final List<String> prefixMatchCandidatesList;
    
    if (strokeDigitSequence.length() <= USE_PREFIX_DATA_MAX_STROKE_COUNT) {
      prefixMatchCharactersData =
        prefixCharactersDataFromStrokeDigitSequence.get(strokeDigitSequence);
      prefixMatchCandidatesList = (
        prefixMatchCharactersData == null
          ? Collections.emptyList()
          : prefixMatchCharactersData.toCandidateList(candidateComparator)
      );
    }
    else {
      prefixMatchCharactersData = new CharactersData("");
      for (
        final CharactersData charactersData
          :
        exactCharactersDataFromStrokeDigitSequence
          .subMap(
            strokeDigitSequence,
            false,
            strokeDigitSequence + Character.MAX_VALUE,
            false
          )
          .values()
      )
      {
        prefixMatchCharactersData.addData(charactersData);
      }
      prefixMatchCandidatesList =
        prefixMatchCharactersData
          .toCandidateList(candidateComparator, MAX_PREFIX_MATCH_COUNT);
    }
    
    final List<String> candidateList = new ArrayList<>();
    candidateList.addAll(exactMatchCandidatesList);
    candidateList.addAll(prefixMatchCandidatesList);
    
    return candidateList;
  }
  
  private String getFirstCandidate() {
    try {
      return candidateList.get(0);
    }
    catch (IndexOutOfBoundsException exception) {
      return "";
    }
  }
  
  private String getTextBeforeCursor(
    final InputConnection inputConnection,
    final int characterCount
  )
  {
    if (inputIsPassword) {
      return ""; // don't read passwords
    }
    
    return (String) inputConnection.getTextBeforeCursor(characterCount, 0);
  }
  
}
