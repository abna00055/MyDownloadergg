import os

filepath = "/app/src/main/java/com/example/MainActivity.kt"
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Remove touchmove block
touchmove_block = """                                                 // Block all page scrolling/swiping when text selection is active to keep selection perfectly stable
                                                 document.addEventListener('touchmove', function(e) {
                                                     if (isTextSelected()) {
                                                         e.preventDefault();
                                                     }
                                                 }, { passive: false });"""

if touchmove_block in content:
    content = content.replace(touchmove_block, "")
    print("Successfully removed touchmove block!")
else:
    # Try with different indentations or just line by line
    print("Warning: touchmove_block not found precisely, trying line-by-line replacement")
    lines = content.splitlines()
    new_lines = []
    skip = False
    skip_count = 0
    for line in lines:
        if "Block all page scrolling/swiping when text selection is active" in line:
            skip = True
            skip_count = 6
        if skip:
            skip_count -= 1
            if skip_count <= 0:
                skip = False
            continue
        new_lines.append(line)
    content = "\n".join(new_lines)
    print("Line-by-line touchmove removal completed.")

# 2. Update double-tap zoom logic
doubletap_target = """                                                             if (delay < 300) {
                                                                 if (singleTapTimeout) {
                                                                     clearTimeout(singleTapTimeout);
                                                                     singleTapTimeout = null;
                                                                 }
                                                                 // Let native double-tap zoom happen natively
                                                                 lastTapTime = 0;
                                                             }"""

doubletap_replacement = """                                                             if (delay < 300) {
                                                                 if (singleTapTimeout) {
                                                                     clearTimeout(singleTapTimeout);
                                                                     singleTapTimeout = null;
                                                                 }
                                                                 handleDoubleTap();
                                                                 lastTapTime = 0;
                                                             }"""

if doubletap_target in content:
    content = content.replace(doubletap_target, doubletap_replacement)
    print("Successfully replaced doubletap block!")
else:
    print("Warning: doubletap_target not found precisely, searching with subset")
    # Let's search with a slightly smaller target to be robust
    sub_target = "Let native double-tap zoom happen natively"
    for line_idx, line in enumerate(content.splitlines()):
        if sub_target in line:
            print(f"Found sub_target on line {line_idx}")
    
    # Precise replacement for the lines
    target_part = """// Let native double-tap zoom happen natively
                                                                 lastTapTime = 0;"""
    replacement_part = """handleDoubleTap();
                                                                 lastTapTime = 0;"""
    if target_part in content:
        content = content.replace(target_part, replacement_part)
        print("Sub-part replaced successfully!")
    else:
        print("Error: Could not replace double-tap logic!")

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)

print("Replacement script finished!")
