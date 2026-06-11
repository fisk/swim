package org.fisk.swim.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.fisk.swim.ui.Point;
import org.fisk.swim.ui.Range;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;

public class AttributedString {
    @FunctionalInterface
    public interface ClickHandler {
        void onClick(int index);
    }

    public static class AttributeSet {
        private TextColor _foregroundColour;
        private TextColor _backgroundColour;

        public AttributeSet(TextColor foregroundColour, TextColor backgroundColour) {
            _foregroundColour = foregroundColour;
            _backgroundColour = backgroundColour;
        }

        public TextColor foregroundColour() {
            return _foregroundColour;
        }

        public TextColor backgroundColour() {
            return _backgroundColour;
        }
    }

    public static class AttributedStringFragment {
        private String _string;
        private AttributeSet _attributes;

        public AttributedStringFragment(String string, AttributeSet attributes) {
            _string = string;
            _attributes = attributes;
        }

        public AttributeSet getAttributes() {
            return _attributes;
        }

        public String toString() {
            return _string;
        }
    }

    public static record FormatRange(int start, int end, TextColor foregroundColour, TextColor backgroundColour) {
    }

    private List<AttributedStringFragment> _fragments = new ArrayList<>();
    private List<ClickRange> _clickRanges = new ArrayList<>();
    private int _length = 0;
    private int _characterLookupFragmentIndex = -1;
    private int _characterLookupFragmentStart = 0;
    private static final Object CLICK_MAP_LOCK = new Object();
    private static final List<RenderedClickRange> _renderedClickRanges = new ArrayList<>();

    public List<AttributedStringFragment> getFragments() {
        return _fragments;
    }

    public static AttributedString create(String string, TextColor foregroundColour, TextColor backgroundColour) {
        var str = new AttributedString();
        str.append(string, foregroundColour, backgroundColour);
        return str;
    }

    public static AttributedString create(AttributedString other) {
        var str = new AttributedString();
        str._length = other._length;
        var fragments = new ArrayList<AttributedStringFragment>();
        fragments.addAll(other._fragments);
        str._fragments = fragments;
        str._clickRanges = new ArrayList<>(other._clickRanges);
        return str;
    }

    public void append(String string, TextColor foregroundColour, TextColor backgroundColour) {
        _fragments.add(new AttributedStringFragment(string, new AttributeSet(foregroundColour, backgroundColour)));
        _length += string.length();
        resetCharacterLookupCache();
    }

    public void append(AttributedString str) {
        int offset = _length;
        _fragments.addAll(str._fragments);
        _length += str._length;
        for (ClickRange range : str._clickRanges) {
            _clickRanges.add(range.shift(offset));
        }
        resetCharacterLookupCache();
    }

    public AttributedString onClick(int start, int end, ClickHandler handler) {
        if (start < 0 || end < start || end > _length) {
            throw new IllegalArgumentException("Click range out of bounds: " + start + ", " + end + " length: " + _length);
        }
        if (start == end || handler == null) {
            return this;
        }
        _clickRanges.add(new ClickRange(start, end, handler));
        return this;
    }

    public boolean clickAt(int index) {
        if (index < 0 || index >= _length) {
            return false;
        }
        for (int i = _clickRanges.size() - 1; i >= 0; i--) {
            ClickRange range = _clickRanges.get(i);
            if (range.contains(index)) {
                range.handler().onClick(index - range.start());
                return true;
            }
        }
        return false;
    }

    public static void clearRenderedClickRanges() {
        synchronized (CLICK_MAP_LOCK) {
            _renderedClickRanges.clear();
        }
    }

    public static Runnable clickActionAt(Point point) {
        if (point == null) {
            return null;
        }
        synchronized (CLICK_MAP_LOCK) {
            for (int i = _renderedClickRanges.size() - 1; i >= 0; i--) {
                Runnable action = _renderedClickRanges.get(i).actionAt(point);
                if (action != null) {
                    return action;
                }
            }
        }
        return null;
    }

    public AttributedString slice(int start, int end) {
        if (start < 0 || end < start || end > _length) {
            throw new IllegalArgumentException("Slice out of bounds: " + start + ", " + end + " length: " + _length);
        }
        var result = new AttributedString();
        if (start == end) {
            return result;
        }
        int currentX = 0;
        for (var fragment : _fragments) {
            int fragmentLength = fragment._string.length();
            var fragmentRange = Range.create(currentX, currentX + fragmentLength);
            result._length += result.formatFragmentRange(Range.create(start, end), fragmentRange, fragment._attributes,
                    fragment._string);
            currentX += fragmentLength;
        }
        for (ClickRange range : _clickRanges) {
            ClickRange sliced = range.slice(start, end);
            if (sliced != null) {
                result._clickRanges.add(sliced);
            }
        }
        return result;
    }
    
    private int formatFragmentRange(Range range, Range fragmentRange, AttributeSet attrs, String fragmentStr) {
        if (range.getLength() <= 0) {
            return 0;
        }
        range = range.intersection(fragmentRange);
        if (range.getLength() <= 0) {
            return 0;
        }
        var str = fragmentStr.substring(range.getStart() - fragmentRange.getStart(), range.getEnd() - fragmentRange.getStart());
        var newFragment = new AttributedStringFragment(str, attrs);
        _fragments.add(newFragment);
        return str.length();
    }
    
    public void format(int start, int end, TextColor foregroundColour, TextColor backgroundColour) {
        var newAttr = new AttributeSet(foregroundColour, backgroundColour);
        var oldFragments = _fragments;
        int currentX = 0;
        _fragments = new ArrayList<>();
        for (var fragment: oldFragments) {
            int fragmentLength = fragment._string.length();
            var fragmentRange = Range.create(currentX, currentX + fragmentLength);
            if (currentX + fragmentLength <= start || currentX >= end) {
                _fragments.add(fragment);
            } else {
                formatFragmentRange(Range.create(currentX, start), fragmentRange, fragment._attributes, fragment._string);
                formatFragmentRange(Range.create(start, end), fragmentRange, newAttr, fragment._string);
                formatFragmentRange(Range.create(end, currentX + fragmentLength), fragmentRange, fragment._attributes, fragment._string);
            }
            currentX += fragmentLength;
        }
        resetCharacterLookupCache();
    }

    public void format(List<FormatRange> ranges) {
        if (ranges == null || ranges.isEmpty() || _length == 0) {
            return;
        }
        var validRanges = new ArrayList<FormatRange>();
        for (var range : ranges) {
            if (range == null) {
                continue;
            }
            int start = Math.max(0, range.start());
            int end = Math.min(_length, range.end());
            if (end > start) {
                validRanges.add(new FormatRange(start, end, range.foregroundColour(), range.backgroundColour()));
            }
        }
        if (validRanges.isEmpty()) {
            return;
        }
        var text = new StringBuilder(_length);
        var foregroundColours = new TextColor[_length];
        var backgroundColours = new TextColor[_length];
        int currentX = 0;
        for (var fragment : _fragments) {
            int fragmentLength = fragment._string.length();
            text.append(fragment._string);
            Arrays.fill(foregroundColours, currentX, currentX + fragmentLength, fragment._attributes._foregroundColour);
            Arrays.fill(backgroundColours, currentX, currentX + fragmentLength, fragment._attributes._backgroundColour);
            currentX += fragmentLength;
        }

        for (var range : validRanges) {
            Arrays.fill(foregroundColours, range.start(), range.end(), range.foregroundColour());
            Arrays.fill(backgroundColours, range.start(), range.end(), range.backgroundColour());
        }

        _fragments = new ArrayList<>();
        int start = 0;
        String string = text.toString();
        while (start < _length) {
            TextColor foreground = foregroundColours[start];
            TextColor background = backgroundColours[start];
            int end = start + 1;
            while (end < _length
                    && Objects.equals(foreground, foregroundColours[end])
                    && Objects.equals(background, backgroundColours[end])) {
                ++end;
            }
            _fragments.add(new AttributedStringFragment(
                    string.substring(start, end),
                    new AttributeSet(foreground, background)));
            start = end;
        }
        resetCharacterLookupCache();
    }

    private static Logger _log = LogFactory.createLog();
    
    public void insert(String str, int position, TextColor foregroundColour, TextColor backgroundColour) {
        _log.debug("Inserting " + str + " at " + position);
        if (position > _length || position < 0) {
            throw new IllegalArgumentException("Insert out of bounds: " + position + " length: " + _length);
        }
        var newAttr = new AttributeSet(foregroundColour, backgroundColour);
        var oldFragments = _fragments;
        var oldClickRanges = _clickRanges;
        int currentX = 0;
        _fragments = new ArrayList<>();
        _clickRanges = new ArrayList<>();
        boolean inserted = false;
        int length = 0;
        for (var fragment: oldFragments) {
            int fragmentLength = fragment._string.length();
            if (inserted ||
                    currentX + fragmentLength <= position ||
                    currentX >= position + str.length()) {
                _fragments.add(fragment);
                length += fragmentLength;
            } else {
                int splitIndex = position - currentX;
                var preString = fragment._string.substring(0, splitIndex);
                var postString = fragment._string.substring(splitIndex, fragmentLength);
                if (preString.length() > 0) {
                    _fragments.add(new AttributedStringFragment(preString, fragment._attributes));
                    length += preString.length();
                }
                _fragments.add(new AttributedStringFragment(str, newAttr));
                length += str.length();
                if (postString.length() > 0) {
                    _fragments.add(new AttributedStringFragment(postString, fragment._attributes));
                    length += postString.length();
                }
                inserted = true;
            }
            currentX += fragmentLength;
        }
        if (!inserted) {
            _fragments.add(new AttributedStringFragment(str, newAttr));
            length += str.length();
        }
        _length += str.length();
        for (ClickRange range : oldClickRanges) {
            _clickRanges.add(range.insert(position, str.length()));
        }
        if (length != _length) {
            throw new RuntimeException("Unexpected length: " + length + ", expected: " + _length);
        }
        resetCharacterLookupCache();
    }
    
    public void remove(int startPosition, int endPosition) {
        var oldFragments = _fragments;
        var oldClickRanges = _clickRanges;
        int currentX = _length;
        _fragments = new ArrayList<>();
        Collections.reverse(oldFragments);
        for (var fragment: oldFragments) {
            int fragmentLength = fragment._string.length();
            currentX -= fragmentLength;
            var fragmentRange = Range.create(currentX, currentX + fragmentLength);
            if (currentX + fragmentLength <= startPosition ||
                    currentX >= endPosition) {
                _fragments.add(fragment);
            } else {
                formatFragmentRange(Range.create(endPosition, currentX + fragmentLength), fragmentRange, fragment._attributes, fragment._string);
                formatFragmentRange(Range.create(currentX, startPosition), fragmentRange, fragment._attributes, fragment._string);
            }
        }
        Collections.reverse(_fragments);
        _length -= endPosition - startPosition;
        _clickRanges = new ArrayList<>();
        for (ClickRange range : oldClickRanges) {
            ClickRange removed = range.remove(startPosition, endPosition);
            if (removed != null) {
                _clickRanges.add(removed);
            }
        }
        resetCharacterLookupCache();
    }
    
    public AttributedString getCharacter(int position) {
        AttributeSet attributes = attributesAt(position);
        int fragmentStart = _characterLookupFragmentStart;
        var fragment = _fragments.get(_characterLookupFragmentIndex);
        return AttributedString.create(fragment._string.substring(position - fragmentStart, position - fragmentStart + 1),
                attributes._foregroundColour, attributes._backgroundColour);
    }

    public AttributeSet attributesAt(int position) {
        if (position < 0 || position >= _length) {
            throw new RuntimeException("Invalid range at " + position);
        }
        int fragmentIndex = 0;
        int currentX = 0;
        if (_characterLookupFragmentIndex >= 0
                && _characterLookupFragmentIndex < _fragments.size()
                && position >= _characterLookupFragmentStart) {
            fragmentIndex = _characterLookupFragmentIndex;
            currentX = _characterLookupFragmentStart;
        }
        for (int i = fragmentIndex; i < _fragments.size(); ++i) {
            var fragment = _fragments.get(i);
            int fragmentLength = fragment._string.length();
            if (position >= currentX &&
                position + 1 <= currentX + fragmentLength) {
                _characterLookupFragmentIndex = i;
                _characterLookupFragmentStart = currentX;
                return fragment._attributes;
            }
            currentX += fragmentLength;
        }
        throw new RuntimeException("Invalid range at " + currentX);
    }

    private void resetCharacterLookupCache() {
        _characterLookupFragmentIndex = -1;
        _characterLookupFragmentStart = 0;
    }

    public void drawAt(Point point, TextGraphics graphics) {
        int currentX = 0;
        for (var fragment: _fragments) {
            graphics.setBackgroundColor(fragment.getAttributes()._backgroundColour);
            graphics.setForegroundColor(fragment.getAttributes()._foregroundColour);
            graphics.putString(point.getX() + currentX, point.getY(), fragment._string);
            currentX += fragment._string.length();
        }
        registerRenderedClickRanges(point);
    }

    public int length() {
        return _length;
    }
    
    public String toString() {
        var str = new StringBuilder();
        for (var fragment: _fragments) {
            str.append(fragment._string);
        }
        return str.toString();
    }

    private void registerRenderedClickRanges(Point point) {
        if (_clickRanges.isEmpty() || point == null) {
            return;
        }
        synchronized (CLICK_MAP_LOCK) {
            for (ClickRange range : _clickRanges) {
                _renderedClickRanges.add(new RenderedClickRange(point.getX() + range.start(),
                        point.getX() + range.end(), point.getY(), range.handler()));
            }
        }
    }

    private record ClickRange(int start, int end, ClickHandler handler) {
        private ClickRange {
            Objects.requireNonNull(handler, "handler");
        }

        private boolean contains(int index) {
            return index >= start && index < end;
        }

        private ClickRange shift(int offset) {
            return new ClickRange(start + offset, end + offset, handler);
        }

        private ClickRange slice(int sliceStart, int sliceEnd) {
            int nextStart = Math.max(start, sliceStart);
            int nextEnd = Math.min(end, sliceEnd);
            if (nextStart >= nextEnd) {
                return null;
            }
            return new ClickRange(nextStart - sliceStart, nextEnd - sliceStart, handler);
        }

        private ClickRange insert(int position, int length) {
            if (position <= start) {
                return new ClickRange(start + length, end + length, handler);
            }
            if (position < end) {
                return new ClickRange(start, end + length, handler);
            }
            return this;
        }

        private ClickRange remove(int removeStart, int removeEnd) {
            int removedLength = removeEnd - removeStart;
            if (removeEnd <= start) {
                return new ClickRange(start - removedLength, end - removedLength, handler);
            }
            if (removeStart >= end) {
                return this;
            }
            int nextStart = start;
            int nextEnd = end;
            if (removeStart <= start) {
                nextStart = removeStart;
            }
            if (removeStart > start) {
                nextEnd -= Math.min(end, removeEnd) - removeStart;
            } else if (removeEnd < end) {
                nextEnd = removeStart + (end - removeEnd);
            } else {
                nextEnd = nextStart;
            }
            if (removeEnd <= start) {
                nextStart -= removedLength;
                nextEnd -= removedLength;
            }
            if (nextEnd <= nextStart) {
                return null;
            }
            return new ClickRange(nextStart, nextEnd, handler);
        }
    }

    private record RenderedClickRange(int startX, int endX, int y, ClickHandler handler) {
        private Runnable actionAt(Point point) {
            if (point.getY() != y || point.getX() < startX || point.getX() >= endX) {
                return null;
            }
            int index = point.getX() - startX;
            return () -> handler.onClick(index);
        }
    }
}
