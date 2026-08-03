import os
from PIL import Image, ImageDraw

def create_circular_mask(h, w):
    mask = Image.new('L', (w, h), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, w, h), fill=255)
    return mask

def generate_icons(source_path, res_path):
    img = Image.open(source_path).convert("RGBA")
    
    densities = {
        'mdpi': 48,
        'hdpi': 72,
        'xhdpi': 96,
        'xxhdpi': 144,
        'xxxhdpi': 192
    }
    
    for density, size in densities.items():
        # Square icon
        resized_img = img.resize((size, size), Image.Resampling.LANCZOS)
        
        # Create directory if it doesn't exist
        dir_path = os.path.join(res_path, f'mipmap-{density}')
        os.makedirs(dir_path, exist_ok=True)
        
        # Save ic_launcher.png
        launcher_path = os.path.join(dir_path, 'ic_launcher.png')
        resized_img.save(launcher_path, format="PNG")
        
        # Round icon
        mask = create_circular_mask(size, size)
        round_img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        round_img.paste(resized_img, (0,0), mask=mask)
        
        # Save ic_launcher_round.png
        round_path = os.path.join(dir_path, 'ic_launcher_round.png')
        round_img.save(round_path, format="PNG")

if __name__ == "__main__":
    source_image = "/home/ganeshak11/.gemini/antigravity-ide/brain/25d85b06-a510-400f-b02b-5461f710dfd8/fortis_app_icon_1785777375744.png"
    res_directory = "/home/ganeshak11/dev/Fortis-Ecosystem/fortis-mobile/android/app/src/main/res"
    generate_icons(source_image, res_directory)
    print("Icons generated successfully!")
