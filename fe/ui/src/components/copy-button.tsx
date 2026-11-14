import { useState } from 'react'
import { Check, Copy } from 'lucide-react'
import { toast } from 'sonner'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'

type CopyButtonProps = {
  text: string
  className?: string
}

export function CopyButton({ text, className }: CopyButtonProps) {
  const [copied, setCopied] = useState(false)

  return (
    <Button
      variant='ghost'
      size='icon'
      aria-label='Copy to clipboard'
      className={cn(
        'size-6 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100',
        className
      )}
      onClick={(event) => {
        event.stopPropagation()
        void navigator.clipboard.writeText(text).then(() => {
          setCopied(true)
          toast.success('Copied')
          window.setTimeout(() => setCopied(false), 1500)
        })
      }}
    >
      {copied ? <Check className='size-3.5' /> : <Copy className='size-3.5' />}
    </Button>
  )
}
