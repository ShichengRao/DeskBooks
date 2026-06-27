/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: [
          "Inter",
          "-apple-system",
          "BlinkMacSystemFont",
          "Segoe UI",
          "Roboto",
          "system-ui",
          "sans-serif",
        ],
        mono: [
          "ui-monospace",
          "SFMono-Regular",
          "Menlo",
          "Monaco",
          "Consolas",
          "monospace",
        ],
      },
      colors: {
        // tasteful dark/light palette
        ink: {
          50: "#f6f7f9",
          100: "#eceef2",
          200: "#d4d8e0",
          300: "#abb2bf",
          400: "#7a8392",
          500: "#525a68",
          600: "#3a414c",
          700: "#272d36",
          800: "#191d24",
          900: "#0f1217",
        },
        brand: {
          50: "#eef6ff",
          100: "#daeaff",
          200: "#b8d6ff",
          300: "#88baff",
          400: "#5395ff",
          500: "#2a70ff",
          600: "#1c54e6",
          700: "#1842b3",
          800: "#163a8c",
          900: "#152f6e",
        },
        good: { 500: "#22a559", 600: "#1c8b4a" },
        bad: { 500: "#dc2a3c", 600: "#b91d2c" },
        warn: { 500: "#e08a16", 600: "#b86c0e" },
      },
    },
  },
  plugins: [],
};
