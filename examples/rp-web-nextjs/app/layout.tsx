import type { Metadata } from 'next'
import './globals.css'

export const metadata: Metadata = {
  title: 'Age Verification Demo - Verify API Example',
  description: 'Example e-commerce checkout with age verification using walt.id Verify API',
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  )
}
