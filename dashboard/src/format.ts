const fractionDigits = (value: number) => (value >= 100 ? 0 : value >= 10 ? 1 : 2)

export const formatNumber = (value: number) =>
  value.toLocaleString('en-US', { maximumFractionDigits: fractionDigits(Math.abs(value)) })

export const formatRunId = (id: string) => {
  const match = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(id)
  return match ? `${match[1]}-${match[2]}-${match[3]} ${match[4]}:${match[5]} UTC` : id
}
