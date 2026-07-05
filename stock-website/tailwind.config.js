/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        surface: {
          50: '#f8fafc',
          100: '#1e1e2e',
          200: '#181825',
          300: '#11111b',
        },
        accent: {
          green: '#a6e3a1',
          red: '#f38ba8',
          blue: '#89b4fa',
          yellow: '#f9e2af',
          mauve: '#cba6f7',
        },
      },
    },
  },
  plugins: [],
}
