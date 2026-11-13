import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/_authenticated/')({
  component: () => (
    <div className='p-6'>
      <h1 className='text-2xl font-bold'>StarRocks Web Console</h1>
      <p className='text-muted-foreground mt-2'>Overview page (M2).</p>
    </div>
  ),
})
