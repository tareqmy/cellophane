// Bind to Cellophane as a transceiver, send one message, wait for its delivery receipt.
// Run: npm install && npm start
import smpp from 'smpp'

const url = process.env.CELLOPHANE_SMPP ?? 'smpp://localhost:2775'
const session = smpp.connect(url)

session.on('connect', () => {
  session.bind_transceiver({ system_id: 'cellophane', password: 'cellophane' }, (pdu) => {
    if (pdu.command_status !== 0) {
      console.error('bind failed:', pdu.command_status)
      process.exit(1)
    }
    console.log('bound')
    session.submit_sm(
      {
        source_addr_ton: 5,
        source_addr: 'MyApp',
        dest_addr_ton: 1,
        dest_addr_npi: 1,
        destination_addr: '8801711111111',
        registered_delivery: 1,
        short_message: 'Your OTP is 482913',
      },
      (resp) => console.log('submit_sm_resp status', resp.command_status, 'message_id', resp.message_id),
    )
  })
})

// The fake operator sends the delivery receipt as deliver_sm; answer it like a real client must.
session.on('deliver_sm', (pdu) => {
  const receipt = pdu.short_message?.message ?? pdu.short_message
  console.log('deliver_sm:', String(receipt))
  session.send(pdu.response())
  session.unbind(() => session.close())
})

session.on('error', (e) => {
  console.error(e.message)
  process.exit(1)
})
