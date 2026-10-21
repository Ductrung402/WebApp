/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#f0fdf4',
          100: '#dcfce7',
          200: '#bbf7d0',
          400: '#98FB98',
          500: '#4ade80',
          600: '#16a34a',
          700: '#1b5232',
          800: '#144127',
        }
      }
    },
  },
  plugins: [],
}

