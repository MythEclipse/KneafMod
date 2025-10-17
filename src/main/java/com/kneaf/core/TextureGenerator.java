package com.kneaf.core;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Utility class to generate simple spawn egg textures programmatically.
 * Generates a basic spawn egg texture with primary and secondary colors.
 */
public class TextureGenerator {

    /**
     * Generates a spawn egg texture PNG file.
     *
     * @param outputPath the path to save the PNG file
     * @param primaryColor the primary color (background)
     * @param secondaryColor the secondary color (spots)
     * @param width the width of the texture
     * @param height the height of the texture
     * @throws IOException if writing the file fails
     */
    public static void generateSpawnEggTexture(String outputPath, Color primaryColor, Color secondaryColor, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        // Enable antialiasing for smoother edges
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

        // Fill background with primary color
        g2d.setColor(primaryColor);
        g2d.fillOval(0, 0, width, height);

        // Add spots with secondary color
        g2d.setColor(secondaryColor);
        int spotSize = width / 8;
        // Add some random spots
        g2d.fillOval(width / 4, height / 4, spotSize, spotSize);
        g2d.fillOval(width * 3 / 4 - spotSize, height / 4, spotSize, spotSize);
        g2d.fillOval(width / 2 - spotSize / 2, height / 2 - spotSize / 2, spotSize, spotSize);
        g2d.fillOval(width / 4, height * 3 / 4 - spotSize, spotSize, spotSize);
        g2d.fillOval(width * 3 / 4 - spotSize, height * 3 / 4 - spotSize, spotSize, spotSize);

        g2d.dispose();

        // Write to file
        File outputFile = new File(outputPath);
        outputFile.getParentFile().mkdirs(); // Ensure directory exists
        ImageIO.write(image, "PNG", outputFile);
    }

    /**
     * Main method to generate the Shadow Zombie Ninja spawn egg texture.
     * Run this to generate the texture file.
     *
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        try {
            // Colors: black primary, white secondary
            Color primary = new Color(0x000000);
            Color secondary = new Color(0xFFFFFF);

            String outputPath = "src/main/resources/assets/kneafcore/textures/item/shadow_zombie_ninja_spawn_egg.png";
            generateSpawnEggTexture(outputPath, primary, secondary, 16, 16);

            System.out.println("Texture generated successfully at: " + outputPath);
        } catch (IOException e) {
            System.err.println("Failed to generate texture: " + e.getMessage());
            e.printStackTrace();
        }
    }
}