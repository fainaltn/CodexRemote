/** @type {import('next').NextConfig} */
const nextConfig = {
  // Proxy API requests to the Fastify backend during development.
  // In production the web app sits behind the same origin or a reverse proxy.
  async rewrites() {
    const backendUrl =
      process.env.CODEXREMOTE_API_URL ?? "http://localhost:31807";
    return [
      {
        source: "/api/:path*",
        destination: `${backendUrl}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
